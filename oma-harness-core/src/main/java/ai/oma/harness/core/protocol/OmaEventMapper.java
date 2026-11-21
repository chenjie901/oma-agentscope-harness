package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 core 内部的 {@link CanonicalEvent} 转换为 OMA 服务端要求的 wire 事件 JSON。
 *
 * <p>负责生成稳定 {@code sevt_} id（基于 session + 内容摘要）、保证 {@code processed} 先于
 * result、以及各类事件的字段映射。接入方无需直接使用；事件由 core 的 OmaHarness 统一上报。
 */
public final class OmaEventMapper {
    private final String codeSessionId;

    public OmaEventMapper(String codeSessionId) {
        this.codeSessionId = codeSessionId;
    }

    public JsonNode toOmaPayload(CanonicalEvent event) {
        if (event instanceof CanonicalEvent.Native nativeEvent) {
            return nativeEvent.payload();
        }
        ObjectNode payload = Json.object();
        payload.put("uuid", "evt_" + UUID.randomUUID());
        switch (event) {
            case CanonicalEvent.TurnStarted started -> throw new IllegalStateException(
                    "turn state must be published through PUT /worker, not as an event");
            case CanonicalEvent.TurnCompleted completed -> throw new IllegalStateException(
                    "turn state must be published through PUT /worker, not as an event");
            case CanonicalEvent.MessageDelta delta -> {
                payload.put("type", "event_delta");
                putThread(payload, delta.threadId());
                payload.put("event_type", "agent.message");
                payload.put("message_id", delta.messageId());
                payload.put("delta", Json.object().put("type", "text_delta").put("text", nullSafe(delta.text())));
            }
            case CanonicalEvent.MessageCompleted completed -> {
                payload.put("type", "agent.message");
                putThread(payload, completed.threadId());
                String id = completed.messageId() == null ? "evt_" + UUID.randomUUID() : completed.messageId();
                payload.put("id", id);
                payload.put("uuid", id);
                putRequestId(payload, completed.requestId());
                ObjectNode block = payload.putArray("content").addObject();
                block.put("type", "text");
                block.put("text", nullSafe(completed.text()));
            }
            case CanonicalEvent.ThinkingDelta thinking -> {
                payload.put("type", "agent.thinking");
                putThread(payload, thinking.threadId());
                payload.put("message_id", thinking.messageId());
                payload.put("thinking", nullSafe(thinking.text()));
                putRequestId(payload, thinking.requestId());
            }
            case CanonicalEvent.ToolCallStarted started -> {
                payload.put("type", "agent.tool_use");
                putThread(payload, started.threadId());
                payload.put("id", toolUseEventId(started.toolCallId(), started.threadId()));
                payload.put("name", started.name());
                payload.set("input", started.input() == null ? Json.object() : started.input());
            }
            case CanonicalEvent.ToolCallCompleted completed -> {
                payload.put("type", "agent.tool_result");
                putThread(payload, completed.threadId());
                payload.put("tool_use_id", toolUseEventId(completed.toolCallId(), completed.threadId()));
                payload.put("name", completed.name());
                payload.set("input", completed.input() == null ? Json.object() : completed.input());
                payload.put("is_error", completed.error());
                ArrayNode content = payload.putArray("content");
                for (Map<String, Object> item : completed.content() == null ? List.<Map<String, Object>>of() : completed.content()) {
                    content.add(Json.parse(Json.write(item)));
                }
            }
            case CanonicalEvent.ContextCompacted compacted -> {
                payload.put("type", "agent.thread_context_compacted");
                putThread(payload, compacted.threadId());
                payload.put("summary", nullSafe(compacted.summary()));
                payload.put("dropped_messages", compacted.droppedMessages());
            }
            case CanonicalEvent.SessionIdle idle -> throw new IllegalStateException(
                    "session state must be published through PUT /worker, not as an event");
            case CanonicalEvent.SessionRunning running -> throw new IllegalStateException(
                    "session state must be published through PUT /worker, not as an event");
            case CanonicalEvent.SessionError error -> {
                payload.put("type", "session.error");
                payload.put("message", nullSafe(error.message()));
            }
            default -> throw new IllegalArgumentException("unsupported canonical event: " + event);
        }
        return payload;
    }

    public JsonNode turnResultPayload(String subtype, boolean error, long durationMs) {
        ObjectNode payload = Json.object();
        payload.put("type", "result");
        payload.put("uuid", "evt_" + UUID.randomUUID());
        payload.put("subtype", nullSafe(subtype));
        payload.put("is_error", error);
        payload.put("duration_ms", durationMs);
        return payload;
    }

    public static boolean isEphemeral(JsonNode payload) {
        String type = payload == null ? null : Json.text(payload, "type");
        return "event_start".equals(type) || "event_delta".equals(type);
    }

    private String toolUseEventId(String toolCallId, String threadId) {
        String seed = codeSessionId + "\u0000public\u0000tool_use\u0000" + toolCallId;
        if (threadId != null && !threadId.isBlank() && !"root".equals(threadId)) {
            seed += "\u0000thread\u0000" + threadId;
        }
        return "sevt_" + HexFormat.of().formatHex(sha256(seed), 0, 16);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putRequestId(ObjectNode payload, String requestId) {
        if (requestId != null && requestId.startsWith("sevt_")) {
            payload.put("model_request_start_id", requestId);
        }
    }

    private static void putThread(ObjectNode payload, String threadId) {
        if (threadId != null && !threadId.isBlank() && !"root".equals(threadId)) {
            payload.put("thread_id", threadId);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
