package ai.oma.harness.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public sealed interface CanonicalEvent {

    record TurnStarted(String threadId) implements CanonicalEvent {
    }

    record TurnCompleted(String threadId, String stopReason) implements CanonicalEvent {
    }

    record MessageDelta(String threadId, String messageId, String text) implements CanonicalEvent {
    }

    record MessageCompleted(String threadId, String messageId, String text, String requestId) implements CanonicalEvent {
    }

    record ThinkingDelta(String threadId, String messageId, String text, String requestId) implements CanonicalEvent {
    }

    record ToolCallStarted(String threadId, String toolCallId, String name, JsonNode input) implements CanonicalEvent {
    }

    record ToolCallCompleted(String threadId, String toolCallId, String name, JsonNode input,
                             List<Map<String, Object>> content, boolean error) implements CanonicalEvent {
    }

    record ContextCompacted(String threadId, String summary, int droppedMessages) implements CanonicalEvent {
    }

    record SessionIdle() implements CanonicalEvent {
    }

    record SessionRunning() implements CanonicalEvent {
    }

    record SessionError(String message) implements CanonicalEvent {
    }

    record Native(JsonNode payload) implements CanonicalEvent {
    }
}
