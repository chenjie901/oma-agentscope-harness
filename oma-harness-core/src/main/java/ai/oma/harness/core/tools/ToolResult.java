package ai.oma.harness.core.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ToolResult(boolean error, String errorCode, List<Map<String, Object>> content, JsonNode raw) {

    public static ToolResult text(String text) {
        return new ToolResult(false, null, List.of(Map.of("type", "text", "text", text == null ? "" : text)), null);
    }

    public static ToolResult failure(String text) {
        return new ToolResult(true, null,
                List.of(Map.of("type", "text", "text", text == null ? "" : text)), null);
    }

    public String text() {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> item : content == null ? List.<Map<String, Object>>of() : content) {
            Object type = item.get("type");
            if ("text".equals(type) && item.get("text") != null) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(item.get("text"));
            }
        }
        return builder.toString();
    }

    public ToolResult withPrefix(String line) {
        List<Map<String, Object>> prefixed = new ArrayList<>();
        prefixed.add(Map.of("type", "text", "text", line));
        if (content != null) {
            prefixed.addAll(content);
        }
        return new ToolResult(error, errorCode, List.copyOf(prefixed), raw);
    }
}
