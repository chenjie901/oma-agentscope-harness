package ai.oma.harness.core.tools;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;

/** OMA 工具调用客户端：把一次工具调用（含审批）发给服务端并拿回结果。由 ToolRunner 使用。 */
public final class OmaToolClient {
    private static final Logger LOG = LoggerFactory.getLogger(OmaToolClient.class);
    private static final Set<String> ACTIVE_STATES =
            Set.of("pending", "dispatched", "running", "cancel_requested", "finalizing");

    private final OmaConfig config;
    private final HttpClient http;

    public OmaToolClient(OmaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public ToolResult call(String tool, JsonNode input, Duration timeout) {
        return call(tool, input, timeout, config.threadId());
    }

    public ToolResult call(String tool, JsonNode input, Duration timeout, String threadId) {
        String callId = "call_" + UUID.randomUUID();
        ObjectNode body = Json.object();
        body.put("call_id", callId);
        body.put("tool", tool);
        body.set("input", input == null ? Json.object() : input);
        if (timeout != null) {
            body.put("timeout_ms", timeout.toMillis());
        }
        submit(callId, threadId, body);
        return await(callId, threadId, timeout == null ? Duration.ofMinutes(5) : timeout.plusSeconds(30));
    }

    public JsonNode peek(String callId, String threadId) {
        return get(toolUri(callId, threadId) + "?wait_ms=0");
    }

    public void cancel(String callId, String threadId) {
        post(URI.create(toolUri(callId, threadId) + "/cancel"), Json.object());
    }

    private void submit(String callId, String threadId, ObjectNode body) {
        post(URI.create(toolUri("", threadId)), body);
        LOG.debug("tool call submitted tool={} call={} thread={}", Json.text(body, "tool"), callId, threadId);
    }

    private ToolResult await(String callId, String threadId, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode call = get(toolUri(callId, threadId) + "?wait_ms=25000");
            String state = Json.text(call, "state");
            if (state != null && ACTIVE_STATES.contains(state)) {
                continue;
            }
            LOG.debug("tool call settled call={} state={}", callId, state);
            JsonNode result = call.get("result");
            if (result != null && !result.isNull()) {
                return normalize(result, !"succeeded".equals(state), state);
            }
            return new ToolResult(true, state, List.of(Map.of("type", "text", "text", describe(state))), call);
        }
        return new ToolResult(true, "TOOL_OUTCOME_UNKNOWN",
                List.of(Map.of("type", "text", "text", "Tool call did not settle before the wait budget elapsed.")), null);
    }

    private ToolResult normalize(JsonNode result, boolean error, String state) {
        List<Map<String, Object>> content = new ArrayList<>();
        JsonNode rawContent = result.get("content");
        if (rawContent != null && rawContent.isArray()) {
            for (JsonNode item : rawContent) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", Json.text(item, "type"));
                if (item.hasNonNull("text")) {
                    entry.put("text", item.get("text").asText());
                } else {
                    entry.put("text", Json.write(item));
                }
                content.add(entry);
            }
        }
        if (content.isEmpty() && result.hasNonNull("text")) {
            content.add(Map.of("type", "text", "text", result.get("text").asText()));
        }
        String errorCode = error ? state : null;
        return new ToolResult(error, errorCode, content, result);
    }

    private String describe(String state) {
        if (state == null) {
            return "Tool call state is unknown.";
        }
        return switch (state) {
            case "cancelled" -> "Tool call interrupted.";
            case "expired" -> "Tool call expired before execution.";
            case "lost" -> "Remote tool outcome is unknown. Verify external state before retrying.";
            default -> "Tool call failed with state " + state + ".";
        };
    }

    private String toolUri(String callId, String threadId) {
        String base = config.sessionBase().toString() + "/threads/"
                + (threadId == null || threadId.isBlank() ? "root" : threadId) + "/tool-calls";
        return callId == null || callId.isBlank() ? base : base + "/" + callId;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private JsonNode get(String uri) {
        HttpRequest request = base(URI.create(uri))
                .timeout(config.requestTimeout().plusSeconds(10))
                .GET()
                .build();
        return send(request);
    }

    private JsonNode post(URI uri, JsonNode body) {
        HttpRequest request = base(uri)
                .header("Content-Type", "application/json")
                .timeout(config.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.warn("tool call HTTP {} {}", response.statusCode(), truncate(response.body()));
                throw new OmaHttpException(response.statusCode(), "tool_call_failed", response.body());
            }
            String text = response.body();
            return text == null || text.isBlank() ? Json.object() : Json.parse(text);
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "tool call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "tool call interrupted");
        }
    }

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("X-OMA-Session-ID", config.sessionId());
    }
}
