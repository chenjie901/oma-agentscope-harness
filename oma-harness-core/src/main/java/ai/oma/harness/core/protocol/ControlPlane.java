package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制面客户端：上报 worker 的 turn 状态（PUT /worker）、权限/工具刷新请求与批准结果。
 * turn envelope（TurnStarted/TurnCompleted）也通过这里上报，而不是当普通事件发送。core 内部使用。
 */
public final class ControlPlane {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlane.class);

    private final Sink sink;
    private final Map<String, CompletableFuture<Decision>> approvals = new ConcurrentHashMap<>();
    private volatile WorkerMetadata metadata;

    public ControlPlane(Sink sink, WorkerMetadata metadata) {
        this.sink = sink;
        this.metadata = metadata == null ? WorkerMetadata.empty() : metadata;
    }

    public WorkerMetadata metadata() {
        return metadata;
    }

    public void updateMetadata(WorkerMetadata next) {
        this.metadata = next == null ? WorkerMetadata.empty() : next;
    }

    public static boolean isInterrupt(JsonNode payload) {
        String type = Json.text(payload, "type");
        if ("user.interrupt".equals(type) || "interrupt".equals(type)) {
            return true;
        }
        if (!"control_request".equals(type)) {
            return false;
        }
        JsonNode request = Json.nullable(payload, "request");
        return request != null && "interrupt".equals(Json.text(request, "subtype"));
    }

    public boolean handles(JsonNode payload) {
        String type = Json.text(payload, "type");
        return "control_request".equals(type) || "control_response".equals(type) || "keep_alive".equals(type);
    }

    public boolean handle(JsonNode payload) {
        String type = Json.text(payload, "type");
        if ("keep_alive".equals(type)) {
            return true;
        }
        if ("control_response".equals(type)) {
            resolve(payload);
            return true;
        }
        if ("control_request".equals(type)) {
            return handleRequest(payload);
        }
        return false;
    }

    public CompletableFuture<Decision> requestApproval(
            String toolName, String toolUseId, JsonNode input, String threadId, Duration timeout) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        approvals.put(requestId, future);

        ObjectNode request = Json.object();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", toolName);
        if (toolUseId != null && !toolUseId.isBlank()) {
            request.put("tool_use_id", toolUseId);
        }
        if (input != null) {
            request.set("input", input);
        }
        if (threadId != null && !threadId.isBlank() && !"root".equals(threadId)) {
            request.put("session_thread_id", threadId);
        }

        ObjectNode payload = Json.object();
        payload.put("type", "control_request");
        payload.put("uuid", requestId);
        payload.put("request_id", requestId);
        payload.set("request", request);

        try {
            sink.emitControl(payload);
            LOG.info("approval requested id={} tool={} thread={}",
                    requestId, toolName, threadId);
        } catch (RuntimeException e) {
            approvals.remove(requestId);
            future.complete(Decision.deny("Approval request could not be delivered"));
            return future;
        }

        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(error -> Decision.deny("Approval timed out"))
                .whenComplete((decision, error) -> approvals.remove(requestId));
    }

    public Decision await(CompletableFuture<Decision> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.deny("Approval interrupted");
        } catch (ExecutionException e) {
            return Decision.deny("Approval failed");
        }
    }

    public void interruptApprovals(String message) {
        approvals.forEach((requestId, future) -> {
            if (approvals.remove(requestId, future)) {
                future.complete(Decision.deny(message));
            }
        });
    }

    private boolean handleRequest(JsonNode payload) {
        JsonNode request = Json.nullable(payload, "request");
        String subtype = request == null ? null : Json.text(request, "subtype");
        String requestId = Json.text(payload, "request_id");
        String failure = null;

        if ("set_model".equals(subtype)) {
            String requested = Json.text(request, "model");
            String model = requested == null || requested.isBlank() || "default".equals(requested)
                    ? null : requested;
            try {
                WorkerMetadata next = metadata.withModel(model);
                sink.saveMetadata(next);
                metadata = next;
                sink.applyModel(model);
            } catch (RuntimeException e) {
                failure = "Could not apply model";
                LOG.warn("control set_model failed: {}", e.getMessage());
            }
        } else if (!"initialize".equals(subtype) && !"interrupt".equals(subtype)) {
            failure = "Unsupported Brain control request";
        }

        ObjectNode response = Json.object();
        response.put("type", "control_response");
        response.put("uuid", UUID.randomUUID().toString());
        if (requestId != null) {
            response.put("request_id", requestId);
        }
        ObjectNode body = response.putObject("response");
        body.put("request_id", requestId == null ? "" : requestId);
        if (failure == null) {
            body.put("subtype", "success");
            body.set("response", Json.object());
        } else {
            body.put("subtype", "error");
            body.put("error", failure);
        }
        sink.emitControl(response);
        return true;
    }

    private void resolve(JsonNode payload) {
        JsonNode response = Json.nullable(payload, "response");
        if (response == null) {
            return;
        }
        String requestId = Json.text(response, "request_id");
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        CompletableFuture<Decision> future = approvals.get(requestId);
        if (future == null) {
            return;
        }
        if (!"success".equals(Json.text(response, "subtype"))) {
            future.complete(Decision.deny("Approval response failed"));
            return;
        }
        JsonNode decision = Json.nullable(response, "response");
        if (decision == null || !decision.isObject()) {
            future.complete(Decision.deny("Approval response was empty"));
            return;
        }
        String behavior = Json.text(decision, "behavior");
        LOG.info("approval response id={} behavior={} updated={} external={}",
                requestId, behavior,
                        Json.nullable(decision, "updatedInput") != null,
                        Json.nullable(decision, "externalResult") != null);
        boolean allowed = "allow".equals(behavior);
        String message = Json.text(decision, "message");
        if (message == null) {
            message = Json.text(decision, "denyMessage");
        }
        future.complete(new Decision(allowed, Json.nullable(decision, "updatedInput"), message,
                Json.nullable(decision, "externalResult")));
    }

    public interface Sink {
        void emitControl(JsonNode payload);

        void saveMetadata(WorkerMetadata metadata);

        void applyModel(String model);
    }

    public record Decision(boolean allowed, JsonNode updatedInput, String message, JsonNode externalResult) {
        public static Decision allow(JsonNode updatedInput) {
            return new Decision(true, updatedInput, null, null);
        }

        public static Decision deny(String message) {
            return new Decision(false, null, message, null);
        }

        public String denyMessage() {
            return message == null ? "Tool use was denied" : message;
        }
    }
}
