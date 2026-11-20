package ai.oma.harness.core.model;

import java.util.List;
import java.util.Map;

public record ModelRequest(
        String model,
        String system,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        int maxTokens,
        Double temperature,
        String purpose) {

    public ModelRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
        purpose = purpose == null || purpose.isBlank() ? "main" : purpose;
    }
}
