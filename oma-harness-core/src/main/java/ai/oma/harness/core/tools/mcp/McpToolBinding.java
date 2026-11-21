package ai.oma.harness.core.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record McpToolBinding(String displayName, String serverName, String remoteName,
                             String description, JsonNode inputSchema) {

    public Map<String, Object> schemaMap() {
        if (inputSchema == null || !inputSchema.isObject()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return JsonNodeToMap.convert(inputSchema);
    }

    private static final class JsonNodeToMap {
        static Map<String, Object> convert(JsonNode node) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        }

        private static Object value(JsonNode node) {
            if (node.isObject()) {
                return convert(node);
            }
            if (node.isArray()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                node.forEach(item -> list.add(value(item)));
                return list;
            }
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isNumber()) {
                return node.numberValue();
            }
            return null;
        }
    }
}
