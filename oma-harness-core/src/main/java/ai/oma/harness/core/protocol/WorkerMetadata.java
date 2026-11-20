package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class WorkerMetadata {
    private final ObjectNode raw;

    private WorkerMetadata(ObjectNode raw) {
        this.raw = raw;
    }

    public static WorkerMetadata empty() {
        return new WorkerMetadata(Json.object());
    }

    public static WorkerMetadata parse(JsonNode workerState) {
        JsonNode worker = workerState == null ? null : workerState.get("worker");
        JsonNode metadata = worker == null ? null : worker.get("external_metadata");
        if (metadata == null || !metadata.isObject()) {
            return empty();
        }
        return new WorkerMetadata(((ObjectNode) metadata).deepCopy());
    }

    public String model() {
        return Json.text(raw, "model");
    }

    public String permissionMode() {
        return Json.text(raw, "permission_mode");
    }

    public PendingAction pendingAction() {
        JsonNode node = Json.nullable(raw, "pending_action");
        if (node == null || !node.isObject()) {
            return null;
        }
        return new PendingAction(
                Json.text(node, "session_thread_id"),
                Json.text(node, "tool_name"),
                Json.text(node, "action_description"),
                Json.text(node, "tool_use_id"),
                Json.text(node, "request_id"),
                Json.nullable(node, "input"));
    }

    public WorkerMetadata withModel(String model) {
        ObjectNode copy = raw.deepCopy();
        if (model == null || model.isBlank()) {
            copy.remove("model");
        } else {
            copy.put("model", model);
        }
        return new WorkerMetadata(copy);
    }

    public WorkerMetadata withPermissionMode(String mode) {
        ObjectNode copy = raw.deepCopy();
        if (mode == null || mode.isBlank()) {
            copy.remove("permission_mode");
        } else {
            copy.put("permission_mode", mode);
        }
        return new WorkerMetadata(copy);
    }

    public WorkerMetadata withPendingAction(PendingAction action) {
        ObjectNode copy = raw.deepCopy();
        if (action == null) {
            copy.putNull("pending_action");
            return new WorkerMetadata(copy);
        }
        ObjectNode node = copy.putObject("pending_action");
        putIfPresent(node, "session_thread_id", action.sessionThreadId());
        putIfPresent(node, "tool_name", action.toolName());
        putIfPresent(node, "action_description", action.actionDescription());
        putIfPresent(node, "tool_use_id", action.toolUseId());
        putIfPresent(node, "request_id", action.requestId());
        if (action.input() != null) {
            node.set("input", action.input());
        }
        return new WorkerMetadata(copy);
    }

    public WorkerMetadata withTaskSummary(String summary) {
        ObjectNode copy = raw.deepCopy();
        if (summary == null) {
            copy.putNull("task_summary");
        } else {
            copy.put("task_summary", summary);
        }
        return new WorkerMetadata(copy);
    }

    public ObjectNode toJson() {
        return raw.deepCopy();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    public record PendingAction(
            String sessionThreadId,
            String toolName,
            String actionDescription,
            String toolUseId,
            String requestId,
            JsonNode input) {
    }
}
