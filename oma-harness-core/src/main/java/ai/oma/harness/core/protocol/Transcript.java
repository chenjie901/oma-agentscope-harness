package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Transcript {
    private Transcript() {
    }

    public static ObjectNode user(String uuid, String text) {
        return message("user", uuid, text);
    }

    public static ObjectNode assistant(String uuid, String text) {
        return message("assistant", uuid, text);
    }

    public static String newUUID() {
        return "evt_" + UUID.randomUUID();
    }

    public static List<RestoredMessage> parse(List<JsonNode> events) {
        List<RestoredMessage> messages = new ArrayList<>();
        for (JsonNode event : events) {
            JsonNode payload = event.get("payload");
            if (payload == null) {
                continue;
            }
            String type = Json.text(payload, "type");
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }
            String text = textOf(payload.get("message"));
            if (text != null && !text.isBlank()) {
                messages.add(new RestoredMessage(type, text));
            }
        }
        return messages;
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

    public record RestoredMessage(String role, String text) {
    }
}
