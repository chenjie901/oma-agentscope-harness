package ai.oma.harness.core.agent;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.compaction.CompactionConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.protocol.WorkerMetadata;
import ai.oma.harness.core.resources.Skill;
import ai.oma.harness.core.tools.ToolResult;
import ai.oma.harness.core.transport.InboundEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmaAgentSupportTest {

    @Test
    void deliverEmitsTurnEnvelopeInOrderAndPersistsUserMessage() {
        FakeSupport support = new FakeSupport();
        FakeContext context = new FakeContext();
        support.start(context);

        support.deliver(userEvent("e1", Json.parse(
                "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}")));

        assertEquals(List.of("TurnStarted", "SessionRunning", "TurnCompleted", "SessionIdle"),
                context.eventKinds);
        assertEquals(1, context.persisted.size());
        assertEquals("hello", context.persisted.get(0).get("message").get("content").get(0).get("text").asText());
    }

    @Test
    void publishEventAggregatesFineGrainedEventsIntoOmaEvents() {
        FakeSupport support = new FakeSupport();
        FakeContext context = new FakeContext();
        support.start(context);

        support.publishEvent(new TextBlockStartEvent("reply-1", "text"));
        support.publishEvent(new TextBlockDeltaEvent("reply-1", "text", "hel"));
        support.publishEvent(new TextBlockDeltaEvent("reply-1", "text", "lo"));
        support.publishEvent(new TextBlockEndEvent("reply-1", "text"));

        support.publishEvent(new ToolCallStartEvent("reply-1", "call-1", "echo"));
        support.publishEvent(new ToolCallDeltaEvent("reply-1", "call-1", "echo", "{\"text\":"));
        support.publishEvent(new ToolCallDeltaEvent("reply-1", "call-1", "echo", "\"hi\"}"));
        support.publishEvent(new ToolCallEndEvent("reply-1", "call-1", "echo"));

        support.publishEvent(new ToolResultStartEvent("reply-1", "call-1", "echo"));
        support.publishEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "echo", "ECHO:"));
        support.publishEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "echo", " hi"));
        support.publishEvent(new ToolResultEndEvent("reply-1", "call-1", "echo", ToolResultState.SUCCESS));

        List<CanonicalEvent> omaEvents = context.emitted.stream()
                .filter(e -> e instanceof CanonicalEvent.MessageCompleted
                        || e instanceof CanonicalEvent.ToolCallStarted
                        || e instanceof CanonicalEvent.ToolCallCompleted)
                .toList();
        assertEquals(3, omaEvents.size());
        CanonicalEvent.MessageCompleted message = (CanonicalEvent.MessageCompleted) omaEvents.get(0);
        assertEquals("hello", message.text());
        CanonicalEvent.ToolCallStarted started = (CanonicalEvent.ToolCallStarted) omaEvents.get(1);
        assertEquals("call-1", started.toolCallId());
        assertEquals("hi", started.input().get("text").asText());
        CanonicalEvent.ToolCallCompleted completed = (CanonicalEvent.ToolCallCompleted) omaEvents.get(2);
        assertEquals("ECHO: hi", completed.content().get(0).get("text"));
        assertFalse(completed.error());
    }

    @Test
    void publishEventEmitsStreamEventSequenceForText() {
        FakeSupport support = new FakeSupport();
        FakeContext context = new FakeContext();
        support.start(context);

        support.publishEvent(new TextBlockStartEvent("reply-1", "text"));
        support.publishEvent(new TextBlockDeltaEvent("reply-1", "text", "hel"));
        support.publishEvent(new TextBlockDeltaEvent("reply-1", "text", "lo"));
        support.publishEvent(new TextBlockEndEvent("reply-1", "text"));

        List<JsonNode> streams = context.nativePayloads.stream()
                .filter(p -> "stream_event".equals(p.get("type").asText()))
                .toList();
        assertEquals(5, streams.size());
        assertEquals("message_start", streams.get(0).get("event").get("type").asText());
        assertEquals("reply-1", streams.get(0).get("event").get("message").get("id").asText());
        assertEquals("content_block_start", streams.get(1).get("event").get("type").asText());
        assertEquals("text", streams.get(1).get("event").get("content_block").get("type").asText());
        assertEquals("content_block_delta", streams.get(2).get("event").get("type").asText());
        assertEquals("hel", streams.get(2).get("event").get("delta").get("text").asText());
        assertEquals("lo", streams.get(3).get("event").get("delta").get("text").asText());
        assertEquals("content_block_stop", streams.get(4).get("event").get("type").asText());
        streams.forEach(p -> assertTrue(p.has("uuid")));

        CanonicalEvent.MessageCompleted message = context.emitted.stream()
                .filter(e -> e instanceof CanonicalEvent.MessageCompleted)
                .map(e -> (CanonicalEvent.MessageCompleted) e)
                .findFirst()
                .orElseThrow();
        assertTrue(message.messageId().startsWith("sevt_"));
        assertEquals("cse_1", streams.get(0).get("session_id").asText());
    }

    @Test
    void streamEventFinalIdIsStableAcrossReplies() {
        FakeSupport first = new FakeSupport();
        FakeContext firstContext = new FakeContext();
        first.start(firstContext);
        first.publishEvent(new TextBlockStartEvent("reply-x", "text"));
        first.publishEvent(new TextBlockDeltaEvent("reply-x", "text", "hi"));
        first.publishEvent(new TextBlockEndEvent("reply-x", "text"));

        FakeSupport second = new FakeSupport();
        FakeContext secondContext = new FakeContext();
        second.start(secondContext);
        second.publishEvent(new TextBlockStartEvent("reply-x", "text"));
        second.publishEvent(new TextBlockDeltaEvent("reply-x", "text", "different text"));
        second.publishEvent(new TextBlockEndEvent("reply-x", "text"));

        CanonicalEvent.MessageCompleted firstMessage = firstContext.emitted.stream()
                .filter(e -> e instanceof CanonicalEvent.MessageCompleted)
                .map(e -> (CanonicalEvent.MessageCompleted) e)
                .findFirst()
                .orElseThrow();
        CanonicalEvent.MessageCompleted secondMessage = secondContext.emitted.stream()
                .filter(e -> e instanceof CanonicalEvent.MessageCompleted)
                .map(e -> (CanonicalEvent.MessageCompleted) e)
                .findFirst()
                .orElseThrow();
        assertTrue(firstMessage.messageId().startsWith("sevt_"));
        assertEquals(firstMessage.messageId(), secondMessage.messageId());
    }

    @Test
    void interruptDoesNotStartTurn() {
        FakeSupport support = new FakeSupport();
        FakeContext context = new FakeContext();
        support.start(context);

        support.deliver(new InboundEvent(1, "e-int", "user.interrupt", null, "cse_1",
                Json.object(), Json.object()));

        assertTrue(context.eventKinds.isEmpty());
        assertTrue(context.persisted.isEmpty());
    }

    @Test
    void ignoresNonUserMessageEvents() {
        FakeSupport support = new FakeSupport();
        FakeContext context = new FakeContext();
        support.start(context);

        support.deliver(new InboundEvent(1, "e-x", "agent.message", null, "cse_1",
                Json.object(), Json.object()));
        support.deliver(userEvent("e-blank", Json.parse(
                "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"  \"}]}}")));

        assertTrue(context.eventKinds.isEmpty());
        assertTrue(context.persisted.isEmpty());
    }

    @Test
    void compactionEmitsEventAndPersistsSummaryWhenHistoryExceedsThreshold() {
        List<Transcript.RestoredMessage> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(new Transcript.RestoredMessage(i % 2 == 0 ? "user" : "assistant", "history-" + i));
        }
        CompactingSupport support = new CompactingSupport();
        FakeContext context = new FakeContext("compressed summary", history);
        support.start(context);

        support.deliver(userEvent("e-compact", Json.parse(
                "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"go\"}]}}")));

        assertTrue(context.eventKinds.contains("ContextCompacted"));
        CanonicalEvent.ContextCompacted compacted = context.emitted.stream()
                .filter(e -> e instanceof CanonicalEvent.ContextCompacted)
                .map(e -> (CanonicalEvent.ContextCompacted) e)
                .findFirst()
                .orElseThrow();
        assertEquals("compressed summary", compacted.summary());
        assertTrue(compacted.droppedMessages() > 0);
        assertTrue(context.persisted.stream().anyMatch(p ->
                "user".equals(p.get("type").asText())
                        && p.get("message").get("content").get(0).get("text").asText()
                                .contains(Transcript.COMPACTED_MARKER_OPEN)));
    }

    @Test
    void compactionFailureDegradesWithoutEmittingCompactedEvent() {
        List<Transcript.RestoredMessage> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(new Transcript.RestoredMessage("user", "history-" + i));
        }
        CompactingSupport support = new CompactingSupport();
        FakeContext context = new FakeContext(null, history);
        support.start(context);

        support.deliver(userEvent("e-compact-fail", Json.parse(
                "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"go\"}]}}")));

        assertFalse(context.eventKinds.contains("ContextCompacted"));
        assertTrue(context.eventKinds.contains("TurnCompleted"));
    }

    private static InboundEvent userEvent(String id, JsonNode payload) {
        return new InboundEvent(1, id, "user.message", null, "cse_1", payload, Json.object());
    }

    private static final class FakeSupport extends OmaAgentSupport {
        FakeSupport() {
            super(testConfig(), SessionContext.of("m", SessionContext.BACKEND_MESSAGES,
                    "instructions", "/work", List.of(), java.util.Map.of()));
        }

        private static OmaConfig testConfig() {
            return OmaConfig.builder()
                    .endpoint("http://127.0.0.1:48080")
                    .sessionId("cse_1")
                    .ingressToken("tok")
                    .workerEpoch(1)
                    .build();
        }

        @Override
        protected ReActAgent buildAgent(String systemPrompt) {
            assertTrue(systemPrompt.contains("# Platform rules"));
            assertTrue(systemPrompt.contains("instructions"));
            return ReActAgent.builder()
                    .name("fake")
                    .sysPrompt(systemPrompt)
                    .model(new EmptyModel())
                    .maxIters(1)
                    .build();
        }
    }

    private static final class CompactingSupport extends OmaAgentSupport {
        CompactingSupport() {
            super(testConfig(), SessionContext.of("m", SessionContext.BACKEND_MESSAGES,
                    "instructions", "/work", List.of(), java.util.Map.of()));
        }

        private static OmaConfig testConfig() {
            return OmaConfig.builder()
                    .endpoint("http://127.0.0.1:48080")
                    .sessionId("cse_1")
                    .ingressToken("tok")
                    .workerEpoch(1)
                    .build();
        }

        @Override
        protected CompactionConfig compactionConfig() {
            return CompactionConfig.builder()
                    .triggerMessages(3)
                    .keepMessages(1)
                    .keepTokens(0)
                    .prune(null)
                    .build();
        }

        @Override
        protected ReActAgent buildAgent(String systemPrompt) {
            return ReActAgent.builder()
                    .name("compacting-fake")
                    .sysPrompt(systemPrompt)
                    .model(new EmptyModel())
                    .maxIters(1)
                    .build();
        }
    }

    private static final class EmptyModel extends ChatModelBase {
        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "empty-test-model";
        }
    }

    private static final class FakeContext implements AgentContext {
        private final List<String> eventKinds = new ArrayList<>();
        private final List<CanonicalEvent> emitted = new ArrayList<>();
        private final List<JsonNode> nativePayloads = new ArrayList<>();
        private final List<ObjectNode> persisted = new ArrayList<>();
        private final String chatSummary;
        private final List<Transcript.RestoredMessage> restored;

        FakeContext() {
            this(null, List.of());
        }

        FakeContext(String chatSummary) {
            this(chatSummary, List.of());
        }

        FakeContext(String chatSummary, List<Transcript.RestoredMessage> restored) {
            this.chatSummary = chatSummary;
            this.restored = restored;
        }

        @Override
        public String sessionId() {
            return "cse_1";
        }

        @Override
        public String threadId() {
            return "root";
        }

        @Override
        public void emit(CanonicalEvent event) {
            eventKinds.add(event.getClass().getSimpleName());
            emitted.add(event);
        }

        @Override
        public void emitNative(JsonNode payload) {
            nativePayloads.add(payload);
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout) {
            return ToolResult.text("");
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout, String threadId) {
            return ToolResult.text("");
        }

        @Override
        public ToolResult webTool(String remoteName, JsonNode input, String threadId) {
            return ToolResult.text("");
        }

        @Override
        public ToolResult portUrl(int port, String threadId) {
            return ToolResult.text("");
        }

        @Override
        public ModelReply chat(ModelRequest request) {
            if (chatSummary == null) {
                throw new IllegalStateException("chat failed");
            }
            return new ModelReply("end_turn", List.of(java.util.Map.of("type", "text", "text", chatSummary)),
                    0, 0, Json.object(), "req_compact");
        }

        @Override
        public List<Skill> skills() {
            return List.of();
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public List<Transcript.RestoredMessage> restoredMessages() {
            return restored;
        }

        @Override
        public WorkerMetadata metadata() {
            return WorkerMetadata.empty();
        }

        @Override
        public void persistInternal(ObjectNode payload) {
            persisted.add(payload);
        }

        @Override
        public ControlPlane.Decision requestApproval(String toolName, String toolUseId, JsonNode input, Duration timeout) {
            return ControlPlane.Decision.allow(null);
        }
    }
}
