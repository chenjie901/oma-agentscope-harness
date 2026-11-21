package ai.oma.harness.core.agentscope;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.observability.SessionMdc;
import ai.oma.harness.core.model.ImageBlocks;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.model.OmaModelClient;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.ThinkingBlock;
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

/**
 * AgentScope {@link ChatModelBase} 的 OMA 实现：把 AgentScope 的模型调用桥接到 OMA 模型代理。
 *
 * <p>接入方不直接 new 这个类，而是用 {@code OmaAgentSupport.chatModel()} 取得由 core 创建好的实例，
 * 在 {@code buildAgent} 中传给 agent 的 model。这样所有模型请求都会经过 OMA 代理（含
 * chat_completions 后端自动切换），并记录 {@link #lastRequestId()} 供事件去重使用。
 *
 * <p>职责：把 AgentScope 的 messages/tools 转换为 OMA 的 ModelRequest，再把 OMA 返回的 text /
 * tool_use 内容块转换回 AgentScope 的 ChatResponse。
 */
public final class OmaChatModel extends ChatModelBase {
    private final OmaModelClient client;
    private final String sessionId;
    private volatile String model;
    private volatile Runnable requestHook = () -> {
    };
    private volatile String lastRequestId;

    public OmaChatModel(OmaConfig config, String model, String apiBackend) {
        this.client = new OmaModelClient(config, apiBackend);
        this.sessionId = config.sessionId();
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
        ModelRequest request = new ModelRequest(
                model,
                systemOf(messages),
                historyOf(messages),
                toolPayloads(tools),
                maxTokens(options),
                temperature(options),
                "main");
        return Flux.create(sink -> {
            Map<String, String> previousMdc = SessionMdc.captureAndPut(sessionId);
            try {
                requestHook.run();
                ModelReply reply = client.send(request,
                        piece -> {
                            if (piece != null && !piece.isEmpty()) {
                                sink.next(textChunk(piece));
                            }
                        },
                        reasoning -> {
                            if (reasoning != null && !reasoning.isEmpty()) {
                                sink.next(thinkingChunk(reasoning));
                            }
                        });
                lastRequestId = reply.requestId();
                List<ContentBlock> finalBlocks = finalBlocks(reply);
                if (!finalBlocks.isEmpty()) {
                    sink.next(ChatResponse.builder()
                            .id("oma_" + UUID.randomUUID())
                            .content(finalBlocks)
                            .usage(new ChatUsage((int) reply.inputTokens(), (int) reply.outputTokens(), 0.0))
                            .finishReason(reply.stopReason())
                            .build());
                }
                sink.complete();
            } catch (RuntimeException e) {
                sink.error(e);
            } finally {
                SessionMdc.restore(previousMdc);
            }
        });
    }

    private ChatResponse textChunk(String text) {
        return ChatResponse.builder()
                .id("oma_" + UUID.randomUUID())
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private ChatResponse thinkingChunk(String reasoning) {
        return ChatResponse.builder()
                .id("oma_" + UUID.randomUUID())
                .content(List.of(ThinkingBlock.builder().thinking(reasoning).build()))
                .build();
    }

    private List<ContentBlock> finalBlocks(ModelReply reply) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> block : reply.content()) {
            String type = text(block.get("type"));
            if ("tool_use".equals(type)) {
                Map<String, Object> input = inputOf(block.get("input"));
                blocks.add(new ToolUseBlock(
                        text(block.get("id")),
                        text(block.get("name")),
                        input,
                        Json.write(Json.mapper().valueToTree(input)),
                        null));
            } else if ("thinking".equals(type)) {
                String value = text(block.get("thinking"));
                if (value != null && !value.isBlank()) {
                    blocks.add(ThinkingBlock.builder().thinking(value).build());
                }
            } else if ("redacted_thinking".equals(type)) {
                blocks.add(ThinkingBlock.builder().thinking("[redacted thinking]").build());
            } else if ("image".equals(type)) {
                ImageBlock image = imageBlock(block);
                if (image != null) {
                    blocks.add(image);
                }
            }
        }
        return blocks;
    }

    private ImageBlock imageBlock(Map<String, Object> block) {
        Object sourceValue = block.get("source");
        if (!(sourceValue instanceof Map<?, ?> source)) {
            return null;
        }
        String sourceType = text(source.get("type"));
        if ("base64".equals(sourceType)) {
            String data = text(source.get("data"));
            if (data.isBlank()) {
                return null;
            }
            String mediaType = text(source.get("media_type"));
            if (mediaType.isBlank()) {
                mediaType = ImageBlocks.DEFAULT_MEDIA_TYPE;
            }
            return new ImageBlock(new Base64Source(mediaType, data));
        }
        if ("url".equals(sourceType)) {
            String url = text(source.get("url"));
            if (url.isBlank()) {
                return null;
            }
            return new ImageBlock(new URLSource(url, text(source.get("media_type"))));
        }
        return null;
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
                } else if (block instanceof ImageBlock imageBlock) {
                    Map<String, Object> image = imagePayload(imageBlock);
                    if (image != null) {
                        blocks.add(image);
                    }
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

    private Map<String, Object> imagePayload(ImageBlock image) {
        Source source = image.getSource();
        if (source instanceof Base64Source base64) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "base64");
            payload.put("media_type", base64.getMediaType());
            payload.put("data", base64.getData());
            return Map.of("type", "image", "source", payload);
        }
        if (source instanceof URLSource url) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "url");
            payload.put("url", url.getUrl());
            if (url.getMimeType() != null && !url.getMimeType().isBlank()) {
                payload.put("media_type", url.getMimeType());
            }
            return Map.of("type", "image", "source", payload);
        }
        return null;
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
