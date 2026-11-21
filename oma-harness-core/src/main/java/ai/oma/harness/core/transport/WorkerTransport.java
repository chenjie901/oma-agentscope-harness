package ai.oma.harness.core.transport;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class WorkerTransport {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerTransport.class);
    private static final int GET_ATTEMPTS = 10;
    private static final long RETRY_BASE_MILLIS = 100;
    private static final long RETRY_MAX_MILLIS = 2_000;
    private static final long PUT_DEDUP_NANOS = Duration.ofMillis(300).toNanos();

    private final OmaConfig config;
    private final HttpClient http;
    private final Object putLock = new Object();
    private volatile long epoch;
    private String lastPutKey;
    private long lastPutNanos;
    private JsonNode lastPutResponse;

    public WorkerTransport(OmaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public long register() {
        ObjectNode body = Json.object();
        JsonNode response = post(config.workerUri("register"), body);
        long registered = Json.longValue(response, "worker_epoch", config.workerEpoch());
        if (registered <= 0) {
            throw new OmaHttpException(500, "invalid_registration", "worker_epoch missing from registration response");
        }
        track(registered);
        LOG.info("worker registered epoch={}", registered);
        return registered;
    }

    public StreamHandle openStream(long epoch, long fromSequenceNum) {
        track(epoch);
        String query = "?worker_epoch=" + epoch + (fromSequenceNum > 0 ? "&from_sequence_num=" + fromSequenceNum : "");
        HttpRequest.Builder builder = base(config.workerUri("events/stream" + query))
                .header("Accept", "text/event-stream")
                .header("Anthropic-Version", "2023-06-01")
                .timeout(Duration.ofMinutes(30));
        if (fromSequenceNum > 0) {
            builder.header("Last-Event-ID", Long.toString(fromSequenceNum));
        }
        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String detail = readAll(response.body());
                throw error(response.statusCode(), detail);
            }
            return new StreamHandle(response.body());
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "worker stream failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "worker stream interrupted");
        }
    }

    public void emit(long epoch, List<OutboundEvent> events) {
        track(epoch);
        if (events.isEmpty()) {
            return;
        }
        ObjectNode body = Json.object();
        body.put("session_id", config.sessionId());
        body.put("worker_epoch", epoch);
        ArrayNode payloads = body.putArray("events");
        for (OutboundEvent outbound : events) {
            ObjectNode event = payloads.addObject();
            event.set("payload", outbound.payload());
            if (outbound.ephemeral()) {
                event.put("ephemeral", true);
            }
        }
        LOG.debug("worker emit count={} session={} epoch={} types={}", events.size(),
                config.sessionId(), epoch, eventTypes(events));
        post(config.workerUri("events"), body);
    }

    private static String eventTypes(List<OutboundEvent> events) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            OutboundEvent outbound = events.get(i);
            builder.append(Json.text(outbound.payload(), "type"));
            if (outbound.ephemeral()) {
                builder.append(":preview");
            }
        }
        return builder.append(']').toString();
    }

    public JsonNode setWorkerStatus(long epoch, String status, JsonNode externalMetadata,
                                    JsonNode requiresActionDetails) {
        track(epoch);
        ObjectNode body = Json.object();
        body.put("worker_epoch", epoch);
        body.put("worker_status", status);
        if (requiresActionDetails != null) {
            body.set("requires_action_details", requiresActionDetails);
        }
        if (externalMetadata != null) {
            body.set("external_metadata", externalMetadata);
        }
        return put(config.workerBase(), body);
    }

    public JsonNode updateWorkerMetadata(long epoch, JsonNode externalMetadata) {
        track(epoch);
        ObjectNode body = Json.object();
        body.put("worker_epoch", epoch);
        body.set("external_metadata", externalMetadata);
        return put(config.workerBase(), body);
    }

    public DeliveryOutcome acknowledge(long epoch, List<DeliveryUpdate> updates) {
        track(epoch);
        if (updates.isEmpty()) {
            return new DeliveryOutcome(0, 0);
        }
        ObjectNode body = Json.object();
        body.put("session_id", config.sessionId());
        body.put("worker_epoch", epoch);
        ArrayNode items = body.putArray("updates");
        for (DeliveryUpdate update : updates) {
            ObjectNode item = items.addObject();
            item.put("event_id", update.eventId());
            item.put("status", update.status());
        }
        LOG.debug("worker acknowledge count={} updates={}", updates.size(), deliverySummary(updates));
        JsonNode response = post(config.workerUri("events/delivery"), body);
        DeliveryOutcome outcome = new DeliveryOutcome(
                Json.longValue(response, "applied", 0),
                Json.longValue(response, "ignored", 0));
        LOG.debug("worker acknowledge result applied={} ignored={}", outcome.applied(), outcome.ignored());
        return outcome;
    }

    private static String deliverySummary(List<DeliveryUpdate> updates) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < updates.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            DeliveryUpdate update = updates.get(i);
            builder.append(update.eventId()).append(':').append(update.status());
        }
        return builder.append(']').toString();
    }

    public JsonNode getWorker(long epoch) {
        track(epoch);
        return getWithRetry(URI.create(config.workerBase() + "?worker_epoch=" + epoch), null);
    }

    public JsonNode getSubagentThread(String threadId, long epoch) {
        track(epoch);
        return get(config.workerUri("subagent-threads/" + threadId + "?worker_epoch=" + epoch), threadId);
    }

    public void appendInternalEvents(long epoch, List<JsonNode> payloads) {
        track(epoch);
        if (payloads.isEmpty()) {
            return;
        }
        ObjectNode body = Json.object();
        body.put("worker_epoch", epoch);
        ArrayNode events = body.putArray("events");
        for (JsonNode payload : payloads) {
            events.addObject().set("payload", payload);
        }
        LOG.debug("worker internal events append count={} session={} epoch={} types={}", payloads.size(),
                config.sessionId(), epoch, payloadTypes(payloads));
        post(config.workerUri("internal-events"), body);
    }

    private static String payloadTypes(List<JsonNode> payloads) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < payloads.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Json.text(payloads.get(i), "type"));
        }
        return builder.append(']').toString();
    }

    public InternalEventsPage readInternalEvents(long epoch, String cursor, boolean subagents) {
        track(epoch);
        StringBuilder query = new StringBuilder("?worker_epoch=").append(epoch);
        if (subagents) {
            query.append("&subagents=true");
        }
        if (cursor != null && !cursor.isBlank()) {
            query.append("&cursor=").append(cursor);
        }
        JsonNode response = getWithRetry(config.workerUri("internal-events" + query), null);
        return new InternalEventsPage(
                Json.elements(response, "data"),
                Json.text(response, "next_cursor"));
    }

    public Heartbeat heartbeat(long epoch) {
        track(epoch);
        ObjectNode body = Json.object();
        body.put("session_id", config.sessionId());
        body.put("worker_epoch", epoch);
        JsonNode response = post(config.workerUri("heartbeat"), body);
        return new Heartbeat(
                Json.longValue(response, "worker_epoch", epoch),
                Json.text(response, "worker_lease_expires_at"));
    }

    public void reportDiagnostics(long epoch, List<JsonNode> lines) {
        track(epoch);
        ObjectNode body = Json.object();
        body.put("session_id", config.sessionId());
        body.put("worker_epoch", epoch);
        body.set("lines", Json.array().addAll(lines));
        post(config.workerUri("diagnostics"), body);
        LOG.debug("worker diagnostics reported epoch={} lines={}", epoch, lines.size());
    }

    public void uploadImageAttachment(long epoch, String sha256, byte[] bytes, String mediaType) {
        track(epoch);
        HttpRequest request = base(config.workerUri("image-attachments/" + sha256))
                .header("Content-Type", mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType)
                .timeout(config.requestTimeout())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        sendBytes(request);
        LOG.debug("worker image attachment uploaded sha={} bytes={}", sha256, bytes.length);
    }

    public byte[] readMessageAttachment(long epoch, String fileId) {
        track(epoch);
        HttpRequest request = base(config.workerUri("message-attachments/" + fileId + "/content"))
                .header("Accept", "*/*")
                .timeout(config.requestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "attachment read failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "attachment read interrupted");
        }
    }

    public JsonNode sendOtlp(long epoch, String kind, byte[] bytes, String contentType) {
        track(epoch);
        HttpRequest request = base(config.workerUri("otlp/v1/" + kind))
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), response.body());
            }
            return response.body() == null || response.body().isBlank()
                    ? Json.object() : Json.parse(response.body());
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "otlp export failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "otlp export interrupted");
        }
    }

    private void sendBytes(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), response.body());
            }
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "request interrupted");
        }
    }

    private JsonNode get(URI uri, String threadId) {
        HttpRequest.Builder builder = base(uri)
                .header("Accept", "application/json")
                .timeout(config.requestTimeout());
        if (threadId != null && !threadId.isBlank() && !"root".equals(threadId)) {
            builder.header("X-OMA-Session-Thread-ID", threadId);
        }
        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), response.body());
            }
            String text = response.body();
            return text == null || text.isBlank() ? Json.object() : Json.parse(text);
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "request interrupted");
        }
    }

    private JsonNode getWithRetry(URI uri, String threadId) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= GET_ATTEMPTS; attempt++) {
            try {
                return get(uri, threadId);
            } catch (OmaHttpException e) {
                if (e.epochTerminal()) {
                    throw e;
                }
                failure = e;
            } catch (RuntimeException e) {
                failure = e;
            }
            if (attempt < GET_ATTEMPTS) {
                LOG.debug("worker GET retry {}/{} after {}", attempt, GET_ATTEMPTS,
                        failure instanceof OmaHttpException http ? "status " + http.status() : failure.getMessage());
                sleep(Math.min(RETRY_MAX_MILLIS, RETRY_BASE_MILLIS * (1L << Math.min(attempt - 1, 10))));
            }
        }
        throw failure == null ? new OmaHttpException(0, "transport_error", "request failed") : failure;
    }

    private JsonNode post(URI uri, JsonNode body) {
        HttpRequest request = base(uri)
                .header("Content-Type", "application/json")
                .timeout(config.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), response.body());
            }
            String text = response.body();
            return text == null || text.isBlank() ? Json.object() : Json.parse(text);
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "request interrupted");
        }
    }

    private JsonNode put(URI uri, JsonNode body) {
        String serialized = Json.write(body);
        String key = uri + "\n" + serialized;
        long now = System.nanoTime();
        synchronized (putLock) {
            if (lastPutKey != null && lastPutKey.equals(key) && now - lastPutNanos < PUT_DEDUP_NANOS) {
                LOG.debug("worker PUT coalesced uri={}", uri);
                return lastPutResponse == null ? Json.object() : lastPutResponse;
            }
        }
        HttpRequest request = base(uri)
                .header("Content-Type", "application/json")
                .timeout(config.requestTimeout())
                .PUT(HttpRequest.BodyPublishers.ofString(serialized, StandardCharsets.UTF_8))
                .build();
        JsonNode response = sendPut(request);
        synchronized (putLock) {
            lastPutKey = key;
            lastPutNanos = System.nanoTime();
            lastPutResponse = response;
        }
        return response;
    }

    private JsonNode sendPut(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw error(response.statusCode(), response.body());
            }
            String text = response.body();
            return text == null || text.isBlank() ? Json.object() : Json.parse(text);
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "request interrupted");
        }
    }

    private HttpRequest.Builder base(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("X-OMA-Session-Id", config.sessionId());
        long current = epoch;
        if (current > 0) {
            builder.header("X-Worker-Epoch", Long.toString(current));
        }
        return builder;
    }

    private void track(long value) {
        if (value > 0) {
            epoch = value;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OmaHttpException error(int status, String body) {
        String code = "http_" + status;
        String message = body;
        try {
            JsonNode json = Json.parse(body);
            JsonNode error = json.get("error");
            if (error != null && error.isObject()) {
                String type = Json.text(error, "type");
                String detail = Json.text(error, "message");
                if (type != null) {
                    code = type;
                }
                if (detail != null) {
                    message = detail;
                }
            }
        } catch (RuntimeException ignored) {
            message = body;
        }
        LOG.warn("worker request failed status={} code={} message={}", status, code, truncate(message));
        return new OmaHttpException(status, code, message == null ? "request failed" : message);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public record OutboundEvent(JsonNode payload, boolean ephemeral) {
        public static OutboundEvent of(JsonNode payload) {
            return new OutboundEvent(payload, false);
        }

        public static OutboundEvent preview(JsonNode payload) {
            return new OutboundEvent(payload, true);
        }
    }

    public record DeliveryUpdate(String eventId, String status) {
        public static DeliveryUpdate received(String eventId) {
            return new DeliveryUpdate(eventId, "received");
        }

        public static DeliveryUpdate processing(String eventId) {
            return new DeliveryUpdate(eventId, "processing");
        }

        public static DeliveryUpdate processed(String eventId) {
            return new DeliveryUpdate(eventId, "processed");
        }
    }

    public record DeliveryOutcome(long applied, long ignored) {
    }

    public record InternalEventsPage(List<JsonNode> events, String nextCursor) {
        public boolean hasMore() {
            return nextCursor != null && !nextCursor.isBlank();
        }
    }

    public record Heartbeat(long workerEpoch, String leaseExpiresAt) {
    }

    public static final class StreamHandle implements AutoCloseable {
        private final InputStream body;

        StreamHandle(InputStream body) {
            this.body = body;
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }
}
