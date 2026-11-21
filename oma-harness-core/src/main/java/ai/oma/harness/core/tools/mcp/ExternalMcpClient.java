package ai.oma.harness.core.tools.mcp;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 外部 MCP server 客户端：按 MCP 协议发现工具、调用工具，供 ExternalMcpRegistry 管理。core 内部使用。 */
public final class ExternalMcpClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalMcpClient.class);
    private static final String PROTOCOL_2024 = "2024-11-05";
    private static final String PROTOCOL_2025 = "2025-03-26";
    private static final String NOT_FORWARDED_HEADER = "X-Oma-Mcp-Not-Forwarded";
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(185);
    private static final int MAX_NOT_FORWARDED_MESSAGE = 500;

    private final McpServerConfig server;
    private final OmaConfig config;
    private final HttpClient http;
    private final URI gateway;
    private final boolean legacyVersion;

    private volatile String sessionId;
    private volatile String protocolVersion = PROTOCOL_2025;
    private volatile boolean authFailed;
    private volatile boolean sessionRejected;
    private volatile boolean connected;

    public ExternalMcpClient(McpServerConfig server, OmaConfig config) {
        this.server = server;
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.legacyVersion = server.isSse();
        this.gateway = buildGateway(config, server);
    }

    private static URI buildGateway(OmaConfig config, McpServerConfig server) {
        String encoded = URLEncoder.encode(server.url().toString(), StandardCharsets.UTF_8);
        return URI.create(config.sessionBase() + "/mcp?mcp_url=" + encoded);
    }

    public McpServerConfig server() {
        return server;
    }

    public boolean authFailed() {
        return authFailed;
    }

    public boolean sessionRejected() {
        return sessionRejected;
    }

    public void connect(String threadId) {
        long started = System.nanoTime();
        ObjectNodeRequest request = new ObjectNodeRequest("initialize");
        com.fasterxml.jackson.databind.node.ObjectNode params = request.params();
        params.put("protocolVersion", legacyVersion ? PROTOCOL_2024 : PROTOCOL_2025);
        params.putObject("capabilities");
        com.fasterxml.jackson.databind.node.ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "oma-agentscope-harness");
        clientInfo.put("version", "1.0.0");
        server.headers().forEach(request::putHeader);
        RpcResponse response = post(request, threadId, INIT_TIMEOUT, true);
        JsonNode result = response.result();
        String negotiated = result == null ? null : text(result, "protocolVersion");
        if (negotiated != null && !negotiated.isBlank()) {
            protocolVersion = negotiated;
        }
        notifyInitialized(threadId);
        connected = true;
        LOG.debug("mcp connected server={} prefix={} type={} protocol={} duration_ms={}",
                server.name(), server.prefix(), server.type(), protocolVersion, elapsedMs(started));
    }

    private void notifyInitialized(String threadId) {
        ObjectNodeRequest request = new ObjectNodeRequest("notifications/initialized", true);
        server.headers().forEach(request::putHeader);
        post(request, threadId, INIT_TIMEOUT, false);
    }

    public List<McpToolBinding> listTools(String threadId) {
        long started = System.nanoTime();
        ObjectNodeRequest request = new ObjectNodeRequest("tools/list");
        server.headers().forEach(request::putHeader);
        RpcResponse response = post(request, threadId, LIST_TIMEOUT, true);
        JsonNode result = response.result();
        List<McpToolBinding> tools = new ArrayList<>();
        if (result == null) {
            return tools;
        }
        JsonNode toolArray = result.get("tools");
        if (toolArray == null || !toolArray.isArray()) {
            return tools;
        }
        for (JsonNode tool : toolArray) {
            String remoteName = text(tool, "name");
            if (remoteName == null || remoteName.isBlank() || server.disabledTools().contains(remoteName)) {
                continue;
            }
            JsonNode schema = tool.get("inputSchema");
            String displayName = server.toolName(remoteName);
            String description = text(tool, "description");
            tools.add(new McpToolBinding(displayName, server.name(), remoteName,
                    description == null ? "" : description, schema));
        }
        LOG.debug("mcp tools discovered server={} prefix={} count={} duration_ms={}",
                server.name(), server.prefix(), tools.size(), elapsedMs(started));
        return List.copyOf(tools);
    }

    public ToolResult callTool(McpToolBinding binding, JsonNode arguments, String threadId) {
        long started = System.nanoTime();
        ObjectNodeRequest request = new ObjectNodeRequest("tools/call");
        com.fasterxml.jackson.databind.node.ObjectNode params = request.params();
        params.put("name", binding.remoteName());
        params.set("arguments", arguments == null ? Json.object() : arguments);
        server.headers().forEach(request::putHeader);
        RpcResponse response;
        try {
            response = post(request, threadId, CALL_TIMEOUT, true);
        } catch (McpTransportException e) {
            if (e.isSessionRejected()) {
                return ToolResult.failure("MCP session expired; retry the tool call");
            }
            return ToolResult.failure("MCP call failed: " + e.getMessage());
        }
        if (response.notForwarded()) {
            return ToolResult.failure(response.notForwardedMessage());
        }
        JsonNode result = response.result();
        if (result == null) {
            return ToolResult.failure("MCP call returned no result");
        }
        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            return ToolResult.failure("MCP call returned invalid content");
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (JsonNode block : content) {
            String type = text(block, "type");
            if (!"text".equals(type)) {
                return ToolResult.failure("remote call completed and was not retried: unsupported MCP content type " + type);
            }
            String value = text(block, "text");
            mapped.add(Map.of("type", "text", "text", value == null ? "" : value));
        }
        LOG.debug("mcp tool called server={} tool={} error={} duration_ms={}",
                server.name(), binding.remoteName(), isError, elapsedMs(started));
        return new ToolResult(isError, null, List.copyOf(mapped), result);
    }

    private RpcResponse post(ObjectNodeRequest request, String threadId, Duration timeout, boolean expectResponse) {
        String body = Json.write(request.node());
        HttpRequest.Builder builder = HttpRequest.newBuilder(gateway)
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String thread = threadId == null || threadId.isBlank() ? "root" : threadId;
        if (!"root".equals(thread)) {
            builder.header("X-OMA-Session-Thread-ID", thread);
        }
        String currentSession = sessionId;
        if (currentSession != null) {
            builder.header("Mcp-Session-Id", currentSession);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpTransportException("transport error: " + e.getMessage(), false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("interrupted", false, false);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            authFailed = true;
            throw new McpTransportException("authentication failed: " + status, false, false);
        }
        if (status == 404 && currentSession != null) {
            sessionRejected = true;
            throw new McpTransportException("session rejected: 404", true, false);
        }
        if (status < 200 || status >= 300) {
            throw new McpTransportException("HTTP " + status, false, false);
        }
        String responseSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (responseSession != null && !responseSession.isBlank()) {
            sessionId = responseSession;
        }
        if (!expectResponse) {
            return RpcResponse.empty();
        }
        String raw = response.body();
        JsonNode envelope = parseEnvelope(raw);
        JsonNode error = envelope == null ? null : envelope.get("error");
        if (error != null && !error.isNull()) {
            String message = text(error, "message");
            throw new McpTransportException("MCP error: " + (message == null ? "unknown" : message), false, false);
        }
        boolean notForwarded = "true".equalsIgnoreCase(
                response.headers().firstValue(NOT_FORWARDED_HEADER).orElse(""));
        return new RpcResponse(envelope == null ? null : envelope.get("result"), notForwarded,
                notForwardedMessageFrom(envelope));
    }

    private static String notForwardedMessageFrom(JsonNode envelope) {
        if (envelope == null) {
            return "remote MCP server could not be reached";
        }
        JsonNode error = envelope.get("error");
        String message = error == null ? null : text(error, "message");
        if (message == null || message.isBlank()) {
            return "remote MCP server could not be reached";
        }
        return message.length() > MAX_NOT_FORWARDED_MESSAGE
                ? message.substring(0, MAX_NOT_FORWARDED_MESSAGE)
                : message;
    }

    static JsonNode parseEnvelope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                return Json.parse(trimmed);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        for (String line : trimmed.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("data:")) {
                String data = value.substring(5).trim();
                if (!data.isEmpty() && !"[DONE]".equals(data)) {
                    try {
                        return Json.parse(data);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return null;
    }

    public void resetSession() {
        sessionId = null;
        sessionRejected = false;
        connected = false;
    }

    @Override
    public void close() {
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private record RpcResponse(JsonNode result, boolean notForwarded, String notForwardedMessage) {
        static RpcResponse empty() {
            return new RpcResponse(null, false, null);
        }
    }

    private static final class ObjectNodeRequest {
        private static final java.util.concurrent.atomic.AtomicLong REQUEST_ID =
                new java.util.concurrent.atomic.AtomicLong(1);

        private final com.fasterxml.jackson.databind.node.ObjectNode node = Json.object();
        private final Map<String, String> headers = new HashMap<>();
        private final boolean notification;

        ObjectNodeRequest(String method) {
            this(method, false);
        }

        ObjectNodeRequest(String method, boolean notification) {
            this.notification = notification;
            node.put("jsonrpc", "2.0");
            node.put("method", method);
            if (!notification) {
                node.put("id", REQUEST_ID.getAndIncrement());
            }
        }

        com.fasterxml.jackson.databind.node.ObjectNode params() {
            com.fasterxml.jackson.databind.node.ObjectNode params =
                    (com.fasterxml.jackson.databind.node.ObjectNode) node.get("params");
            if (params == null) {
                params = node.putObject("params");
            }
            return params;
        }

        void putHeader(String key, String value) {
            headers.put(key, value);
        }

        com.fasterxml.jackson.databind.node.ObjectNode node() {
            return node;
        }
    }

    public static final class McpTransportException extends RuntimeException {
        private final boolean sessionRejected;

        McpTransportException(String message, boolean sessionRejected, boolean unused) {
            super(message);
            this.sessionRejected = sessionRejected;
        }

        public boolean isSessionRejected() {
            return sessionRejected;
        }
    }
}
