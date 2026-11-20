package ai.oma.harness.core.persistence;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalEventStoreTest {
    private final AtomicInteger uploads = new AtomicInteger();
    private HttpServer server;
    private WorkerTransport transport;
    private List<JsonNode> remotePayloads = List.of();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/code/sessions/cse_ev/worker/internal-events", this::handleInternalEvents);
        server.start();
        transport = new WorkerTransport(OmaConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .sessionId("cse_ev")
                .ingressToken("token")
                .workerEpoch(1)
                .build());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void hydratesLocalTranscriptFromRemotePrefix(@TempDir Path temp) {
        remotePayloads = List.of(message("evt_1", "hello"), message("evt_2", "world"));
        TranscriptStore store = TranscriptStore.open(temp, "cse_ev");
        store.append(List.of(message("evt_1", "hello")));
        InternalEventStore events = new InternalEventStore(transport, () -> 1L, 50, store);

        List<JsonNode> history = events.synchronize(false);

        assertEquals(2, history.size());
        assertEquals(2, store.records().size());
        assertEquals(0, events.pending());
        store.close();
    }

    @Test
    void queuesLocalTailMissingFromRemote(@TempDir Path temp) {
        remotePayloads = List.of(message("evt_1", "hello"));
        TranscriptStore store = TranscriptStore.open(temp, "cse_ev");
        store.append(List.of(message("evt_1", "hello"), message("evt_2", "world")));
        InternalEventStore events = new InternalEventStore(transport, () -> 1L, 50, store);

        List<JsonNode> history = events.synchronize(false);

        assertEquals(2, history.size());
        assertEquals(1, events.pending());
        store.close();
    }

    @Test
    void rejectsDivergedHistories(@TempDir Path temp) {
        remotePayloads = List.of(message("evt_1", "changed"));
        TranscriptStore store = TranscriptStore.open(temp, "cse_ev");
        store.append(List.of(message("evt_1", "hello")));
        InternalEventStore events = new InternalEventStore(transport, () -> 1L, 50, store);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> events.synchronize(false));

        assertEquals(TranscriptStore.DIVERGED, failure.getMessage());
        assertEquals(1, store.records().size());
        store.close();
    }

    @Test
    void writesLocalTranscriptBeforeUploading(@TempDir Path temp) {
        TranscriptStore store = TranscriptStore.open(temp, "cse_ev");
        InternalEventStore events = new InternalEventStore(transport, () -> 1L, 50, store);

        events.record(message("evt_1", "hello"));

        assertEquals(1, store.records().size());
        assertEquals(1, events.pending());
        assertEquals(0, uploads.get());
        store.close();
    }

    @Test
    void uploadsAndClearsPendingEvents(@TempDir Path temp) {
        TranscriptStore store = TranscriptStore.open(temp, "cse_ev");
        InternalEventStore events = new InternalEventStore(transport, () -> 1L, 50, store);
        events.record(message("evt_1", "hello"));

        events.flush();

        assertEquals(1, uploads.get());
        assertEquals(0, events.pending());
        store.close();
    }

    private void handleInternalEvents(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            var data = Json.array();
            for (JsonNode payload : remotePayloads) {
                data.addObject().set("payload", payload);
            }
            var body = Json.object();
            body.set("data", data);
            respond(exchange, 200, Json.write(body));
            return;
        }
        uploads.incrementAndGet();
        respond(exchange, 200, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ObjectNode message(String uuid, String text) {
        var payload = Json.object();
        payload.put("type", "user");
        payload.put("uuid", uuid);
        payload.putObject("message").put("role", "user").putArray("content")
                .addObject().put("type", "text").put("text", text);
        return payload;
    }
}
