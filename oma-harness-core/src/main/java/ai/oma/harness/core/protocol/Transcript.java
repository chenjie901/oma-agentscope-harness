package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 历史消息的构造与解析工具：把服务端事件流中的 user/assistant 文本消息解析为
 * {@link RestoredMessage}，供 {@code OmaAgentSupport} 恢复到 agent 上下文；也提供构造
 * transcript 事件的静态方法供 core 持久化 assistant 回复。接入方通常不直接使用。
 */
public final class Transcript {
    public static final String COMPACTED_MARKER_OPEN = "<compacted-summary>";
    public static final String COMPACTED_MARKER_CLOSE = "</compacted-summary>";

    private Transcript() {
    }

    public static ObjectNode user(String uuid, String text) {
        return message("user", uuid, text);
    }

    public static ObjectNode assistant(String uuid, String text) {
        return message("assistant", uuid, text);
    }

    /** 压缩摘要以标准 user 消息落盘，带标记包裹，确保后端 internal event 白名单接受并能跨重启恢复。 */
    public static ObjectNode compacted(String uuid, String summary, int droppedMessages) {
        String framed = COMPACTED_MARKER_OPEN + "\n" + (summary == null ? "" : summary)
                + "\n" + COMPACTED_MARKER_CLOSE;
        ObjectNode payload = message("user", uuid, framed);
        payload.put("dropped_messages", droppedMessages);
        return payload;
    }

    public static String newUUID() {
        return "evt_" + UUID.randomUUID();
    }

    public static List<RestoredMessage> parse(List<JsonNode> events) {
        List<RestoredMessage> messages = new ArrayList<>();
        for (JsonNode event : events) {
            JsonNode wrapper = event.get("payload");
            JsonNode payload = wrapper != null ? wrapper : event;
            String type = Json.text(payload, "type");
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }
            String text = textOf(payload.get("message"));
            if (text == null || text.isBlank()) {
                continue;
            }
            if ("user".equals(type) && isCompacted(text)) {
                messages.add(new RestoredMessage("user", unwrapCompacted(text), true));
                continue;
            }
            messages.add(new RestoredMessage(type, text, false));
        }
        return messages;
    }

    private static boolean isCompacted(String text) {
        return text.startsWith(COMPACTED_MARKER_OPEN) && text.contains(COMPACTED_MARKER_CLOSE);
    }

    private static String unwrapCompacted(String text) {
        int from = COMPACTED_MARKER_OPEN.length();
        int to = text.lastIndexOf(COMPACTED_MARKER_CLOSE);
        return text.substring(from, to).strip();
    }

    private static ObjectNode message(String role, String uuid, String text) {
        ObjectNode payload = Json.object();
        payload.put("type", role);
        payload.put("uuid", uuid == null || uuid.isBlank() ? newUUID() : uuid);
        ObjectNode message = payload.putObject("message");
        message.put("role", role);
        ObjectNode block = message.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", text == null ? "" : text);
        return payload;
    }

    private static String textOf(JsonNode message) {
        if (message == null) {
            return null;
        }
        JsonNode content = message.get("content");
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(Json.text(block, "type"))) {
                String text = Json.text(block, "text");
                if (text != null) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.toString();
    }

    public record RestoredMessage(String role, String text, boolean summary) {
        public RestoredMessage(String role, String text) {
            this(role, text, false);
        }
    }
}
