package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerMetadataTest {

    @Test
    void parsesWorkerStateWithMetadata() {
        JsonNode state = Json.parse("""
                {"worker":{"external_metadata":{"model":"deepseek-chat","permission_mode":"default"}}}
                """);

        WorkerMetadata metadata = WorkerMetadata.parse(state);

        assertEquals("deepseek-chat", metadata.model());
        assertEquals("default", metadata.permissionMode());
    }

    @Test
    void returnsEmptyMetadataWhenWorkerMissing() {
        WorkerMetadata metadata = WorkerMetadata.parse(Json.parse("{}"));

        assertNull(metadata.model());
        assertTrue(metadata.toJson().isEmpty());
    }

    @Test
    void keepsUnknownFieldsWhenUpdatingModel() {
        JsonNode state = Json.parse("""
                {"worker":{"external_metadata":{"model":"old","task_summary":"running"}}}
                """);

        WorkerMetadata updated = WorkerMetadata.parse(state).withModel("new");
        JsonNode json = updated.toJson();

        assertEquals("new", json.get("model").asText());
        assertEquals("running", json.get("task_summary").asText());
    }

    @Test
    void roundTripsPendingAction() {
        WorkerMetadata metadata = WorkerMetadata.empty()
                .withPendingAction(new WorkerMetadata.PendingAction(
                        "root", "bash", "Run command", "tool-1", "req-1", Json.parse("{\"command\":\"ls\"}")))
                .withTaskSummary(null);

        JsonNode json = metadata.toJson();
        WorkerMetadata.PendingAction action = metadata.pendingAction();

        assertEquals("bash", json.get("pending_action").get("tool_name").asText());
        assertTrue(json.get("task_summary").isNull());
        assertEquals("tool-1", action.toolUseId());
        assertEquals("ls", action.input().get("command").asText());
    }

    @Test
    void clearsPendingAction() {
        WorkerMetadata metadata = WorkerMetadata.empty()
                .withPendingAction(new WorkerMetadata.PendingAction("root", "bash", "d", "t", "r", null))
                .withPendingAction(null);

        assertNull(metadata.pendingAction());
        assertTrue(metadata.toJson().get("pending_action").isNull());
    }
}
