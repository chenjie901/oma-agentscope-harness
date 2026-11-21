package ai.oma.harness.core.agent;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 AgentScope 的细粒度文本/思考块事件转成 OMA 的 stream_event 序列上报，
 * 让前端能在最终消息到达前逐 token 渲染。后端 workerPreviewConverter 会把
 * message_start/content_block_start/content_block_delta/content_block_stop/message_stop
 * 转成 event_start + event_delta；最终 agent.message 必须用同一稳定 id 收敛 delta 帧。
 */
final class StreamEventPublisher {
    private static final String BLOCK_TEXT = "text";
    private static final String BLOCK_THINKING = "thinking";

    private final AgentContext context;
    private final String codeSessionId;

    private final Map<String, MessageState> messages = new HashMap<>();

    StreamEventPublisher(AgentContext context, String codeSessionId) {
        this.context = context;
        this.codeSessionId = codeSessionId;
    }

    void blockStart(String replyId, String blockId, boolean thinking) {
        MessageState state = messages.computeIfAbsent(replyId, key -> new MessageState());
        if (!state.started) {
            ObjectNode start = newEvent("message_start");
            eventBody(start).set("message", Json.object().put("id", replyId));
            emit(start);
            state.started = true;
        }
        int index = state.index(blockId);
        String blockType = thinking ? BLOCK_THINKING : BLOCK_TEXT;
        ObjectNode payload = newEvent("content_block_start");
        ObjectNode event = eventBody(payload);
        event.put("index", index);
        event.set("content_block", Json.object().put("type", blockType));
        emit(payload);
    }

    void blockDelta(String replyId, String blockId, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        MessageState state = messages.get(replyId);
        if (state == null) {
            return;
        }
        Integer index = state.indices.get(blockId);
        if (index == null) {
            return;
        }
        ObjectNode payload = newEvent("content_block_delta");
        ObjectNode event = eventBody(payload);
        event.put("index", index);
        event.set("delta", Json.object().put("type", "text_delta").put("text", delta));
        emit(payload);
    }

    void blockEnd(String replyId, String blockId) {
        MessageState state = messages.get(replyId);
        if (state == null) {
            return;
        }
        Integer index = state.indices.get(blockId);
        if (index == null) {
            return;
        }
        ObjectNode payload = newEvent("content_block_stop");
        eventBody(payload).put("index", index);
        emit(payload);
    }

    void messageStop(String replyId) {
        MessageState state = messages.remove(replyId);
        if (state == null || !state.started) {
            return;
        }
        emit(newEvent("message_stop"));
    }

    void stopAll() {
        for (String replyId : List.copyOf(messages.keySet())) {
            messageStop(replyId);
        }
    }

    String finalEventId(String replyId, String blockId, boolean thinking) {
        MessageState state = messages.get(replyId);
        int index = state == null ? 0 : state.indices.getOrDefault(blockId, 0);
        return stableAssistantEventId(replyId, index, thinking ? "agent.thinking" : "agent.message");
    }

    private String stableAssistantEventId(String messageId, int index, String eventType) {
        String seed = "assistant-preview-v1\u0000" + messageId + "\u0000" + index + "\u0000" + eventType;
        String value = codeSessionId + "\u0000public\u0000" + seed;
        return "sevt_" + HexFormat.of().formatHex(sha256(value), 0, 16);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode newEvent(String eventType) {
        ObjectNode payload = Json.object();
        payload.put("type", "stream_event");
        payload.put("uuid", "evt_" + UUID.randomUUID());
        payload.put("session_id", codeSessionId);
        payload.putObject("event").put("type", eventType);
        return payload;
    }

    private static ObjectNode eventBody(ObjectNode payload) {
        return (ObjectNode) payload.get("event");
    }

    private void emit(ObjectNode payload) {
        context.emitNative(payload);
    }

    private static final class MessageState {
        private final Map<String, Integer> indices = new HashMap<>();
        private int nextIndex = 0;
        private boolean started = false;

        int index(String blockId) {
            return indices.computeIfAbsent(blockId, key -> nextIndex++);
        }
    }
}
