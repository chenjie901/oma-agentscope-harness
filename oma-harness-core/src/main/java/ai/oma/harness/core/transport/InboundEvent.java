package ai.oma.harness.core.transport;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OMA 服务端下发给 worker 的一条事件（user.message、user.interrupt 等），带 sequence 用于
 * 服务端去重与重放。worker 长轮询拿到后交给 {@code HarnessAgent.deliver(...)} 处理。
 */
public record InboundEvent(
        long sequenceNum,
        String eventId,
        String eventType,
        String eventSubtype,
        String sessionId,
        JsonNode payload,
        JsonNode envelope) {

    public String threadId() {
        JsonNode value = payload == null ? null : payload.get("thread_id");
        return value == null || value.isNull() ? null : value.asText();
    }

    public boolean isInputEvent() {
        return switch (eventType == null ? "" : eventType) {
            case "user.message", "user.interrupt", "user.tool_confirmation", "user.tool_result",
                 "user.custom_tool_result", "system.message" -> true;
            default -> false;
        };
    }
}
