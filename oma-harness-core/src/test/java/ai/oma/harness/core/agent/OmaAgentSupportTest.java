package ai.oma.harness.core.agent;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.claim.SessionContext;
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
        private final List<ObjectNode> persisted = new ArrayList<>();

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
            return null;
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
            return List.of();
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
