package ai.oma.harness.core.model;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * chat_completions 兼容后端的流式 HTTP 客户端：当会话上下文的 api_backend 为
 * {@code chat_completions} 时，OmaModelClient 会用它与后端直接通信。core 内部使用。
 */
public final class ChatCompletionsClient {
    private static final Logger LOG = LoggerFactory.getLogger(ChatCompletionsClient.class);
    private final OmaConfig config;
    private final HttpClient http;

    public ChatCompletionsClient(OmaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public ModelReply send(ModelRequest request) {
        return send(request, null, null);
    }

    public ModelReply send(ModelRequest request, java.util.function.Consumer<String> onTextDelta) {
        return send(request, onTextDelta, null);
    }

    /** 与 {@link #send(ModelRequest)} 相同，但在文本/推理增量 SSE chunk 到达时分别回调。 */
    public ModelReply send(ModelRequest request, java.util.function.Consumer<String> onTextDelta,
                           java.util.function.Consumer<String> onReasoningDelta) {
        LOG.debug("model request start backend=chat_completions model={} purpose={} messages={} tools={} thread={}",
                request.model(), request.purpose(), request.messages().size(), request.tools().size(), config.threadId());
        HttpRequest httpRequest = HttpRequest.newBuilder(config.apiBase().resolve("v1/chat/completions"))
                .header("Authorization", "Bearer " + config.effectiveModelToken())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-OMA-Model-Purpose", request.purpose())
                .header("X-OMA-Session-ID", config.sessionId())
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload(request)), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                String detail = readAll(response.body());
                LOG.warn("model request HTTP {} {}", response.statusCode(), truncate(detail));
                throw new OmaHttpException(response.statusCode(), "model_request_failed", detail);
            }
            try (InputStream stream = response.body()) {
                ModelReply reply = collect(stream, response.headers().firstValue("Request-Id").orElse(null),
                        onTextDelta, onReasoningDelta);
                LOG.debug("model response request_id={} stop={} text_length={} tool_uses={}",
                        reply.requestId(), reply.stopReason(), reply.text().length(), reply.toolUses().size());
                return reply;
            }
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "chat completion request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "chat completion request interrupted");
        }
    }

    private ObjectNode payload(ModelRequest request) {
        ObjectNode body = Json.object();
        body.put("model", request.model());
        body.put("max_completion_tokens", request.maxTokens());
        body.put("stream", true);
        body.put("n", 1);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.system());
        }
        for (Map<String, Object> message : request.messages()) {
            appendMessage(messages, message);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (Map<String, Object> tool : request.tools()) {
                tools.add(Json.parse(Json.write(chatTool(tool))));
            }
        }
        return body;
    }

    private void appendMessage(ArrayNode messages, Map<String, Object> message) {
        String role = text(message.get("role"));
        Object content = message.get("content");
        if ("assistant".equals(role)) {
            StringBuilder answer = new StringBuilder();
            ArrayNode toolCalls = Json.array();
            for (Object item : items(content)) {
                if (!(item instanceof Map<?, ?> block)) {
                    continue;
                }
                String type = text(block.get("type"));
                if ("text".equals(type)) {
                    answer.append(text(block.get("text")));
                } else if ("tool_use".equals(type)) {
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", text(block.get("id")));
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", text(block.get("name")));
                    Object input = block.get("input");
                    function.put("arguments", input == null ? "{}" : Json.write(input));
                }
            }
            ObjectNode entry = messages.addObject();
            entry.put("role", "assistant");
            if (!answer.isEmpty()) {
                entry.put("content", answer.toString());
            }
            if (!toolCalls.isEmpty()) {
                entry.set("tool_calls", toolCalls);
            }
            return;
        }
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> toolMessages = new ArrayList<>();
        for (Object item : items(content)) {
            if (item instanceof Map<?, ?> block) {
                String type = text(block.get("type"));
                if ("tool_result".equals(type)) {
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", block.get("tool_use_id"));
                    toolMessage.put("content", flatten(block.get("content")));
                    toolMessages.add(toolMessage);
                } else if ("text".equals(type)) {
                    answer.append(text(block.get("text")));
                }
            } else if (item != null) {
                answer.append(text(item));
            }
        }
        for (Map<String, Object> toolMessage : toolMessages) {
            messages.add(Json.parse(Json.write(toolMessage)));
        }
        if (!answer.isEmpty()) {
            ObjectNode entry = messages.addObject();
            entry.put("role", "user");
            entry.put("content", answer.toString());
        }
    }

    private Map<String, Object> chatTool(Map<String, Object> tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.get("name"));
        function.put("description", tool.get("description"));
        Object schema = tool.get("input_schema");
        function.put("parameters", schema == null ? Map.of("type", "object", "properties", Map.of()) : schema);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "function");
        entry.put("function", function);
        return entry;
    }

    private ModelReply collect(InputStream stream, String requestId,
                               java.util.function.Consumer<String> onTextDelta,
                               java.util.function.Consumer<String> onReasoningDelta) throws IOException {
        StringBuilder answer = new StringBuilder();
        Map<Integer, ToolCallAccumulator> calls = new LinkedHashMap<>();
        String finishReason = null;
        long inputTokens = 0;
        long outputTokens = 0;
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String payload = line.startsWith("data:") ? line.substring(5).trim() : "";
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }
            JsonNode event = Json.parse(payload);
            JsonNode usage = event.get("usage");
            if (usage != null) {
                inputTokens = Math.max(inputTokens, Json.longValue(usage, "prompt_tokens", Json.longValue(usage, "input_tokens", 0)));
                outputTokens = Math.max(outputTokens, Json.longValue(usage, "completion_tokens", Json.longValue(usage, "output_tokens", 0)));
            }
            for (JsonNode choice : Json.elements(event, "choices")) {
                String reason = Json.text(choice, "finish_reason");
                if (reason != null && !reason.isBlank()) {
                    finishReason = reason;
                }
                JsonNode delta = choice.get("delta");
                if (delta == null) {
                    continue;
                }
                String piece = Json.text(delta, "content");
                if (piece != null && !piece.isEmpty()) {
                    answer.append(piece);
                    if (onTextDelta != null) {
                        onTextDelta.accept(piece);
                    }
                }
                if (onReasoningDelta != null) {
                    String reasoning = Json.text(delta, "reasoning_content");
                    if (reasoning == null) {
                        reasoning = Json.text(delta, "reasoning");
                    }
                    if (reasoning != null && !reasoning.isEmpty()) {
                        onReasoningDelta.accept(reasoning);
                    }
                }
                for (JsonNode call : Json.elements(delta, "tool_calls")) {
                    int index = (int) Json.longValue(call, "index", calls.size());
                    ToolCallAccumulator accumulator = calls.computeIfAbsent(index, key -> new ToolCallAccumulator());
                    String id = Json.text(call, "id");
                    if (id != null && !id.isBlank()) {
                        accumulator.id = id;
                    }
                    JsonNode function = call.get("function");
                    if (function == null) {
                        continue;
                    }
                    String name = Json.text(function, "name");
                    if (name != null && !name.isBlank()) {
                        accumulator.name = name;
                    }
                    String arguments = Json.text(function, "arguments");
                    if (arguments != null) {
                        accumulator.arguments.append(arguments);
                    }
                }
            }
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (!answer.isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", answer.toString());
            content.add(block);
        }
        for (ToolCallAccumulator accumulator : calls.values()) {
            if (accumulator.name == null) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", accumulator.id == null ? "call_" + content.size() : accumulator.id);
            block.put("name", accumulator.name);
            String arguments = accumulator.arguments.toString();
            block.put("input", arguments.isBlank() ? Json.object() : Json.parse(arguments));
            content.add(block);
        }
        return new ModelReply(stopReason(finishReason), List.copyOf(content),
                inputTokens, outputTokens, Json.object(), requestId);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private static String stopReason(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case "tool_calls" -> "tool_use";
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            default -> finishReason;
        };
    }

    private static List<?> items(Object content) {
        if (content instanceof List<?> list) {
            return list;
        }
        return content == null ? List.of() : List.of(content);
    }

    private String flatten(Object content) {
        if (content instanceof String value) {
            return value;
        }
        if (content instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> block) {
                    builder.append(text(block.get("text")));
                } else if (item != null) {
                    builder.append(text(item));
                }
            }
            return builder.toString();
        }
        return content == null ? "" : Json.write(content);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
