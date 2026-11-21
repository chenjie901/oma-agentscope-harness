package ai.oma.harness.core.model;

import java.util.List;
import java.util.Map;

/**
 * 发给 OMA 模型代理的请求：model、system prompt、messages、tools、max_tokens 等。
 * 由 OmaChatModel 从 AgentScope 调用转换而来；接入方无需直接构造。
 */
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
