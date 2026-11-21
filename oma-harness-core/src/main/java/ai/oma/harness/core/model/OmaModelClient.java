package ai.oma.harness.core.model;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OMA 模型代理客户端：按会话的 api_backend 选择消息接口或 chat_completions 接口发起请求，
 * 返回统一的 {@link ModelReply} 并记录 request id。由 OmaChatModel 调用。
 */
public final class OmaModelClient {
    private static final Logger LOG = LoggerFactory.getLogger(OmaModelClient.class);
    private static final String BACKEND_MESSAGES = "messages";
    private static final String BACKEND_CHAT_COMPLETIONS = "chat_completions";

    private final OmaConfig config;
    private final HttpClient http;
    private final ChatCompletionsClient chatCompletions;
    private volatile String backend;

    public OmaModelClient(OmaConfig config) {
        this(config, null);
    }

    public OmaModelClient(OmaConfig config, String backend) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.chatCompletions = new ChatCompletionsClient(config);
        this.backend = backend == null || backend.isBlank() ? null : backend;
    }

    public ModelReply send(ModelRequest request) {
        String resolved = backend;
        if (BACKEND_CHAT_COMPLETIONS.equals(resolved)) {
            return chatCompletions.send(request);
        }
        if (BACKEND_MESSAGES.equals(resolved)) {
            return sendMessages(request);
        }
        try {
            ModelReply reply = sendMessages(request);
            backend = BACKEND_MESSAGES;
            return reply;
        } catch (OmaHttpException e) {
            if (!requiresChatCompletions(e)) {
                throw e;
            }
            ModelReply reply = chatCompletions.send(request);
            backend = BACKEND_CHAT_COMPLETIONS;
            return reply;
        }
    }

    private static boolean requiresChatCompletions(OmaHttpException e) {
        if (e.status() != 400) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("chat_completions") || message.contains("api_backend");
    }

    private ModelReply sendMessages(ModelRequest request) {
        ObjectNode body = Json.object();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        if (request.system() != null && !request.system().isBlank()) {
            body.put("system", request.system());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        for (Map<String, Object> message : request.messages()) {
            messages.add(Json.parse(Json.write(message)));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (Map<String, Object> tool : request.tools()) {
                tools.add(Json.parse(Json.write(tool)));
            }
        }
        LOG.debug("model request start backend=messages model={} purpose={} messages={} tools={} thread={}",
                request.model(), request.purpose(), request.messages().size(), request.tools().size(), config.threadId());
        HttpRequest httpRequest = HttpRequest.newBuilder(config.apiBase().resolve("v1/messages"))
                .header("Authorization", "Bearer " + config.effectiveModelToken())
                .header("Anthropic-Version", "2023-06-01")
                .header("Content-Type", "application/json")
                .header("X-OMA-Model-Purpose", request.purpose())
                .header("X-OMA-Session-ID", config.sessionId())
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.warn("model request HTTP {} {}", response.statusCode(), truncate(response.body()));
                throw new OmaHttpException(response.statusCode(), "model_request_failed", response.body());
            }
            ModelReply reply = parse(Json.parse(response.body()), response.headers().firstValue("Request-Id").orElse(null));
            LOG.debug("model response request_id={} stop={} text_length={} tool_uses={}",
                    reply.requestId(), reply.stopReason(), reply.text().length(), reply.toolUses().size());
            return reply;
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "model request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "model request interrupted");
        }
    }

    private ModelReply parse(JsonNode response, String requestId) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (JsonNode block : Json.elements(response, "content")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String type = Json.text(block, "type");
            entry.put("type", type);
            switch (type == null ? "" : type) {
                case "text" -> entry.put("text", Json.text(block, "text"));
                case "tool_use" -> {
                    entry.put("id", Json.text(block, "id"));
                    entry.put("name", Json.text(block, "name"));
                    entry.put("input", block.get("input"));
                }
                case "thinking" -> entry.put("thinking", Json.text(block, "thinking"));
                default -> entry.put("raw", block);
            }
            content.add(entry);
        }
        JsonNode usage = response.get("usage");
        long inputTokens = usage == null ? 0 : Json.longValue(usage, "input_tokens", 0);
        long outputTokens = usage == null ? 0 : Json.longValue(usage, "output_tokens", 0);
        return new ModelReply(Json.text(response, "stop_reason"), List.copyOf(content),
                inputTokens, outputTokens, response, requestId);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    public URI messagesEndpoint() {
        return config.apiBase().resolve("v1/messages");
    }
}
