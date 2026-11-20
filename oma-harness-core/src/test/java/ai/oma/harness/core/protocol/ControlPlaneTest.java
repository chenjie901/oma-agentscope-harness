package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneTest {

    @Test
    void answersInitializeWithSuccess() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        boolean handled = plane.handle(Json.parse("""
                {"type":"control_request","request_id":"initialize_cse","request":{"subtype":"initialize"}}
                """));

        assertTrue(handled);
        JsonNode response = recorder.controls.get(0);
        assertEquals("control_response", response.get("type").asText());
        assertEquals("success", response.get("response").get("subtype").asText());
        assertEquals("initialize_cse", response.get("response").get("request_id").asText());
    }

    @Test
    void appliesModelAndPersistsMetadata() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        plane.handle(Json.parse("""
                {"type":"control_request","request_id":"r1","request":{"subtype":"set_model","model":"deepseek-chat"}}
                """));

        assertEquals("deepseek-chat", plane.metadata().model());
        assertEquals("deepseek-chat", recorder.saved.get(0).model());
        assertEquals("deepseek-chat", recorder.models.get(0));
        assertEquals("success", recorder.controls.get(0).get("response").get("subtype").asText());
    }

    @Test
    void resetsModelToDefault() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty().withModel("deepseek-chat"));

        plane.handle(Json.parse("""
                {"type":"control_request","request_id":"r2","request":{"subtype":"set_model","model":"default"}}
                """));

        assertEquals(null, plane.metadata().model());
        assertEquals(null, recorder.models.get(0));
    }

    @Test
    void rejectsUnsupportedSubtype() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        plane.handle(Json.parse("""
                {"type":"control_request","request_id":"r3","request":{"subtype":"rewind"}}
                """));

        JsonNode response = recorder.controls.get(0).get("response");
        assertEquals("error", response.get("subtype").asText());
        assertEquals("Unsupported Brain control request", response.get("error").asText());
    }

    @Test
    void ignoresKeepAlive() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        assertTrue(plane.handle(Json.parse("{\"type\":\"keep_alive\"}")));
        assertTrue(recorder.controls.isEmpty());
    }

    @Test
    void resolvesApprovalFromControlResponse() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        CompletableFuture<ControlPlane.Decision> pending = plane.requestApproval(
                "bash", "tool-1", Json.parse("{\"command\":\"ls\"}"), "root", Duration.ofSeconds(5));

        JsonNode request = recorder.controls.get(0);
        String requestId = request.get("request_id").asText();
        assertEquals("can_use_tool", request.get("request").get("subtype").asText());
        assertEquals("bash", request.get("request").get("tool_name").asText());
        assertFalse(request.get("request").has("session_thread_id"));

        plane.handle(Json.parse("""
                {"type":"control_response","response":{"subtype":"success","request_id":"%s",
                 "response":{"behavior":"allow","updatedInput":{"command":"ls -la"}}}}
                """.formatted(requestId)));

        ControlPlane.Decision decision = plane.await(pending);
        assertTrue(decision.allowed());
        assertEquals("ls -la", decision.updatedInput().get("command").asText());
    }

    @Test
    void deniesWhenApprovalTimesOut() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        ControlPlane.Decision decision = plane.await(
                plane.requestApproval("bash", "tool-2", null, "root", Duration.ofMillis(20)));

        assertFalse(decision.allowed());
        assertEquals("Approval timed out", decision.message());
    }

    @Test
    void scopesApprovalsToSubagentThreads() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        plane.requestApproval("Bash", "tool-4", Json.parse("{\"command\":\"ls\"}"), "child-1", Duration.ofSeconds(5));

        assertEquals("child-1", recorder.controls.get(0).get("request").get("session_thread_id").asText());
    }

    @Test
    void interruptsPendingApprovals() {
        Recorder recorder = new Recorder();
        ControlPlane plane = new ControlPlane(recorder, WorkerMetadata.empty());

        CompletableFuture<ControlPlane.Decision> pending = plane.requestApproval(
                "Bash", "tool-3", Json.parse("{\"command\":\"ls\"}"), "root", Duration.ofSeconds(5));
        plane.interruptApprovals("Tool call interrupted.");

        ControlPlane.Decision decision = plane.await(pending);
        assertFalse(decision.allowed());
        assertEquals("Tool call interrupted.", decision.denyMessage());
    }

    @Test
    void detectsInterruptControlRequests() {
        assertTrue(ControlPlane.isInterrupt(Json.parse("""
                {"type":"control_request","request":{"subtype":"interrupt"}}
                """)));
        assertTrue(ControlPlane.isInterrupt(Json.parse("{\"type\":\"user.interrupt\"}")));
        assertFalse(ControlPlane.isInterrupt(Json.parse("""
                {"type":"control_request","request":{"subtype":"initialize"}}
                """)));
    }

    private static final class Recorder implements ControlPlane.Sink {
        private final List<JsonNode> controls = new ArrayList<>();
        private final List<WorkerMetadata> saved = new ArrayList<>();
        private final List<String> models = new ArrayList<>();

        @Override
        public void emitControl(JsonNode payload) {
            controls.add(payload);
        }

        @Override
        public void saveMetadata(WorkerMetadata metadata) {
            saved.add(metadata);
        }

        @Override
        public void applyModel(String model) {
            models.add(model);
        }
    }
}
