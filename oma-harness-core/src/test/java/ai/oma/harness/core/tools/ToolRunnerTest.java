package ai.oma.harness.core.tools;

import ai.oma.harness.core.agent.AgentContext;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.protocol.WorkerMetadata;
import ai.oma.harness.core.resources.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRunnerTest {

    @Test
    void skipsApprovalWhenPermissionAllows() {
        FakeContext context = new FakeContext();
        ToolRunner runner = runner(context, Map.of("bash", "allow"));

        ToolResult result = runner.execute(ToolCatalog.byName("Bash"), "call-1", Json.parse("{\"command\":\"ls\"}"));

        assertNull(context.approvedTool);
        assertEquals(List.of("bash"), context.calls);
        assertTrue(result.text().contains("sandbox"));
    }

    @Test
    void requestsApprovalAndReportsDenyMessage() {
        FakeContext context = new FakeContext();
        context.decision = ControlPlane.Decision.deny("blocked by policy");
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("Bash"), "call-2", Json.parse("{\"command\":\"ls\"}"));

        assertEquals("Bash", context.approvedTool);
        assertTrue(context.calls.isEmpty());
        assertTrue(result.error());
        assertEquals("blocked by policy", result.text());
    }

    @Test
    void executesUpdatedInputAndAnnotatesResult() {
        FakeContext context = new FakeContext();
        context.decision = new ControlPlane.Decision(true, Json.parse("{\"command\":\"ls -la\"}"), null, null);
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("Bash"), "call-3", Json.parse("{\"command\":\"ls\"}"));

        assertEquals("ls -la", context.inputs.get(0).get("command").asText());
        assertTrue(result.text().startsWith("OMA approval changed Bash arguments to {\"command\":\"ls -la\"}."));
    }

    @Test
    void returnsExternalResultWithoutDispatching() {
        FakeContext context = new FakeContext();
        context.decision = new ControlPlane.Decision(true, null, null, Json.parse("""
                {"content":[{"type":"text","text":"{\\"answers\\":[{\\"id\\":\\"q1\\",\\"selected\\":[\\"Yes\\"]}]}"}],"isError":false}
                """));
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("AskUserQuestion"), "call-4", Json.parse("""
                {"questions":[{"id":"q1","question":"Proceed?","options":[{"label":"Yes"},{"label":"No"}]}]}
                """));

        assertTrue(context.calls.isEmpty());
        assertEquals("{\"answers\":[{\"id\":\"q1\",\"selected\":[\"Yes\"]}]}", result.text());
        assertTrue(!result.error());
    }

    @Test
    void failsWhenQuestionHasNoExternalResult() {
        FakeContext context = new FakeContext();
        context.decision = new ControlPlane.Decision(true, null, null, null);
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("AskUserQuestion"), "call-5", Json.parse("""
                {"questions":[{"id":"q1","question":"Proceed?"}]}
                """));

        assertEquals("Missing user question result", result.text());
    }

    @Test
    void rejectsAnswersWithUnknownOptions() {
        FakeContext context = new FakeContext();
        context.decision = new ControlPlane.Decision(true, null, null, Json.parse("""
                {"content":[{"type":"text","text":"{\\"answers\\":[{\\"id\\":\\"q1\\",\\"selected\\":[\\"Maybe\\"]}]}"}],"isError":false}
                """));
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("AskUserQuestion"), "call-6", Json.parse("""
                {"questions":[{"id":"q1","question":"Proceed?","options":[{"label":"Yes"},{"label":"No"}]}]}
                """));

        assertTrue(result.error());
        assertEquals("Answer contains an unknown option", result.text());
    }

    @Test
    void rejectsDuplicateQuestionIds() {
        FakeContext context = new FakeContext();
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("AskUserQuestion"), "call-7", Json.parse("""
                {"questions":[{"id":"q1","question":"A"},{"id":"q1","question":"B"}]}
                """));

        assertEquals("Question ids must be unique", result.text());
    }

    @Test
    void reportsInterruptWhileApprovalPending() {
        FakeContext context = new FakeContext();
        context.cancelled = true;
        context.decision = ControlPlane.Decision.deny("Tool call interrupted.");
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("Bash"), "call-8", Json.parse("{\"command\":\"ls\"}"));

        assertEquals("Tool call interrupted.", result.text());
    }

    @Test
    void dispatchesWebAndPortTools() {
        FakeContext context = new FakeContext();
        context.webResult = ToolResult.text("{\"title\":\"page\"}");
        context.portResult = ToolResult.text("https://port.example");
        ToolRunner runner = runner(context, Map.of());

        ToolResult search = runner.execute(ToolCatalog.byName("web_search"), "call-9", Json.parse("{\"query\":\"oma\"}"));
        ToolResult port = runner.execute(ToolCatalog.byName("get_port_url"), "call-10", Json.parse("{\"port\":3000}"));

        assertEquals("search", context.webCalls.get(0));
        assertEquals(3000, context.ports.get(0));
        assertEquals("{\"title\":\"page\"}", search.text());
        assertEquals("https://port.example", port.text());
    }

    @Test
    void convertsDispatchFailureToUnknownOutcome() {
        FakeContext context = new FakeContext();
        context.failCalls = true;
        ToolRunner runner = runner(context, Map.of("bash", "allow"));

        ToolResult result = runner.execute(ToolCatalog.byName("Bash"), "call-12", Json.parse("{\"command\":\"ls\"}"));

        assertTrue(result.error());
        assertTrue(result.text().startsWith("Remote tool outcome is unknown."));
    }

    @Test
    void rejectsOutOfRangePort() {
        FakeContext context = new FakeContext();
        ToolRunner runner = runner(context, Map.of());

        ToolResult result = runner.execute(ToolCatalog.byName("get_port_url"), "call-11", Json.parse("{\"port\":70000}"));

        assertTrue(context.ports.isEmpty());
        assertEquals("port must be an integer between 1 and 65535", result.text());
    }

    private static ToolRunner runner(FakeContext context, Map<String, String> permissions) {
        SessionContext session = SessionContext.of("model", SessionContext.BACKEND_MESSAGES, null, null,
                List.of("Bash", "web_search", "get_port_url", "ask_user_question"), permissions);
        return new ToolRunner(context, () -> session);
    }

    private static final class FakeContext implements AgentContext {
        private final List<String> calls = new ArrayList<>();
        private final List<JsonNode> inputs = new ArrayList<>();
        private final List<String> webCalls = new ArrayList<>();
        private final List<Integer> ports = new ArrayList<>();
        private ControlPlane.Decision decision = ControlPlane.Decision.allow(null);
        private ToolResult result = ToolResult.text("sandbox result");
        private ToolResult webResult = ToolResult.text("{}");
        private ToolResult portResult = ToolResult.text("https://port.example");
        private String approvedTool;
        private boolean cancelled;
        private boolean failCalls;

        @Override
        public String sessionId() {
            return "cse_test";
        }

        @Override
        public String threadId() {
            return "root";
        }

        @Override
        public void emit(CanonicalEvent event) {
        }

        @Override
        public void emitNative(JsonNode payload) {
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout) {
            return callTool(tool, input, timeout, threadId());
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout, String threadId) {
            calls.add(tool);
            inputs.add(input);
            if (failCalls) {
                throw new IllegalStateException("sandbox unavailable");
            }
            return result;
        }

        @Override
        public ToolResult webTool(String remoteName, JsonNode input, String threadId) {
            webCalls.add(remoteName);
            return webResult;
        }

        @Override
        public ToolResult portUrl(int port, String threadId) {
            ports.add(port);
            return portResult;
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
            return cancelled;
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
        }

        @Override
        public ControlPlane.Decision requestApproval(String toolName, String toolUseId, JsonNode input, Duration timeout) {
            approvedTool = toolName;
            return decision;
        }
    }
}
