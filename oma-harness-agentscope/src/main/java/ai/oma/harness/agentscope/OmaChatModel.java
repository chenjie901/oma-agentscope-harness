package ai.oma.harness.agentscope;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.model.OmaModelClient;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OmaChatModel extends ChatModelBase {
    private final OmaModelClient client;
    private volatile String model;
    private volatile Runnable requestHook = () -> {
    };
    private volatile String lastRequestId;

    public OmaChatModel(OmaConfig config, String model) {
        this(config, model, null);
    }

    public OmaChatModel(OmaConfig config, String model, String apiBackend) {
        this.client = new OmaModelClient(config, apiBackend);
        this.model = model;
    }

    public void setModel(String value) {
        if (value != null && !value.isBlank()) {
            this.model = value;
        }
    }

    public String lastRequestId() {
        return lastRequestId;
    }

    public void onRequest(Runnable hook) {
        this.requestHook = hook == null ? () -> {
        } : hook;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        try {
            requestHook.run();
            ModelReply reply = client.send(new ModelRequest(
                    model,
                    systemOf(messages),
                    historyOf(messages),
                    toolPayloads(tools),
                    maxTokens(options),
                    temperature(options),
                    "main"));
            lastRequestId = reply.requestId();
            return Flux.just(response(reply));
        } catch (RuntimeException e) {
            return Flux.error(e);
        }
    }

    private ChatResponse response(ModelReply reply) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> block : reply.content()) {
            String type = text(block.get("type"));
            if ("text".equals(type)) {
                String value = text(block.get("text"));
                if (!value.isBlank()) {
                    blocks.add(TextBlock.builder().text(value).build());
                }
            } else if ("tool_use".equals(type)) {
                Map<String, Object> input = inputOf(block.get("input"));
                blocks.add(new ToolUseBlock(
                        text(block.get("id")),
                        text(block.get("name")),
                        input,
                        Json.write(Json.mapper().valueToTree(input)),
                        null));
            }
        }
        return ChatResponse.builder()
                .id("oma_" + UUID.randomUUID())
                .content(blocks)
                .usage(new ChatUsage((int) reply.inputTokens(), (int) reply.outputTokens(), 0.0))
                .finishReason(reply.stopReason())
                .build();
    }

    private String systemOf(List<Msg> messages) {
        StringBuilder builder = new StringBuilder();
        for (Msg message : messages) {
            if (message.getRole() != MsgRole.SYSTEM) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(message.getTextContent());
        }
        return builder.toString();
    }

    private List<Map<String, Object>> historyOf(List<Msg> messages) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (Msg message : messages) {
            if (message.getRole() == MsgRole.SYSTEM) {
                continue;
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    blocks.add(Map.of("type", "text", "text", textBlock.getText()));
                } else if (block instanceof ToolUseBlock toolUse) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", "tool_use");
                    entry.put("id", toolUse.getId());
                    entry.put("name", toolUse.getName());
                    entry.put("input", toolUse.getInput() == null ? Map.of() : toolUse.getInput());
                    blocks.add(entry);
                } else if (block instanceof ToolResultBlock toolResult) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", "tool_result");
                    entry.put("tool_use_id", toolResult.getId());
                    entry.put("content", outputText(toolResult));
                    entry.put("is_error", false);
                    blocks.add(entry);
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.getRole() == MsgRole.ASSISTANT ? "assistant" : "user");
            entry.put("content", blocks);
            history.add(entry);
        }
        return history;
    }

    private String outputText(ToolResultBlock block) {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock item : block.getOutput()) {
            if (item instanceof TextBlock textBlock) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(textBlock.getText());
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> toolPayloads(List<ToolSchema> tools) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ToolSchema tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.getName());
            entry.put("description", tool.getDescription());
            entry.put("input_schema", tool.getParameters() == null ? Map.of("type", "object") : tool.getParameters());
            payloads.add(entry);
        }
        return payloads;
    }

    private int maxTokens(GenerateOptions options) {
        if (options == null) {
            return 8192;
        }
        if (options.getMaxTokens() != null && options.getMaxTokens() > 0) {
            return options.getMaxTokens();
        }
        if (options.getMaxCompletionTokens() != null && options.getMaxCompletionTokens() > 0) {
            return options.getMaxCompletionTokens();
        }
        return 8192;
    }

    private Double temperature(GenerateOptions options) {
        return options == null ? null : options.getTemperature();
    }

    private Map<String, Object> inputOf(Object input) {
        if (input == null) {
            return Map.of();
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return converted;
        }
        JsonNode node = Json.mapper().valueToTree(input);
        return Json.mapper().convertValue(node, Map.class);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
