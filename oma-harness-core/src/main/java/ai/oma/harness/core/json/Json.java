package ai.oma.harness.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;

/** 全局 Jackson 工具：统一的 ObjectMapper 与 JsonNode 构造/读取方法，core 内部使用。 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON payload", e);
        }
    }

    public static JsonNode parse(InputStream stream) {
        try {
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON stream", e);
        }
    }

    public static JsonNode parse(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON bytes", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value is not serializable", e);
        }
    }

    public static String write(JsonNode node) {
        return write((Object) node);
    }

    public static JsonNode nullable(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child;
    }

    public static String text(JsonNode node, String field) {
        JsonNode child = nullable(node, field);
        return child == null ? null : child.asText();
    }

    public static long longValue(JsonNode node, String field, long fallback) {
        JsonNode child = nullable(node, field);
        if (child == null) {
            return fallback;
        }
        if (child.isNumber()) {
            return child.asLong();
        }
        String text = child.asText();
        return text == null || text.isBlank() ? fallback : Long.parseLong(text.trim());
    }

    public static List<JsonNode> elements(JsonNode node, String field) {
        JsonNode child = nullable(node, field);
        if (child == null || !child.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(child.spliterator(), false).toList();
    }
}
