package ai.oma.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ModelReply(
        String stopReason,
        List<Map<String, Object>> content,
        long inputTokens,
        long outputTokens,
        JsonNode raw,
        String requestId) {

    public String text() {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type")) && block.get("text") != null) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(block.get("text"));
            }
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> toolUses() {
        List<Map<String, Object>> uses = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if ("tool_use".equals(block.get("type"))) {
                uses.add(block);
            }
        }
        return uses;
    }

    public boolean hasToolUse() {
        return !toolUses().isEmpty();
    }
}
