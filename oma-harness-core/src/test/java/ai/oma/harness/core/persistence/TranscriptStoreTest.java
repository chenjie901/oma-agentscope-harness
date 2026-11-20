package ai.oma.harness.core.persistence;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptStoreTest {

    @Test
    void appendsRecordsAndResumesSequence(@TempDir Path temp) {
        TranscriptStore store = TranscriptStore.open(temp, "cse_store");
        store.append(List.of(message("evt_1", "hello"), message("evt_2", "world")));
        store.close();

        TranscriptStore reopened = TranscriptStore.open(temp, "cse_store");
        List<TranscriptStore.Record> records = reopened.records();

        assertEquals(2, records.size());
        assertEquals(0, records.get(0).seq());
        assertEquals("evt_1", records.get(0).uuid());
        assertEquals("user", records.get(0).type());
        reopened.append(List.of(message("evt_3", "again")));
        assertEquals(2, reopened.records().get(2).seq());
        reopened.close();
    }

    @Test
    void writesHeaderOnFirstOpen(@TempDir Path temp) {
        TranscriptStore store = TranscriptStore.open(temp, "cse_header");
        store.append(List.of(message("evt_1", "hello")));
        store.close();

        JsonNode header = store.header();
        assertEquals(1, header.get("version").asInt());
        assertEquals("cse_header", header.get("session_id").asText());
        assertTrue(header.hasNonNull("created_at"));
        assertTrue(Files.exists(Path.of(temp.toString(), "sessions", "cse_header", "transcript.jsonl")));
    }

    @Test
    void repairsTornTailBeforeAppending(@TempDir Path temp) throws IOException {
        TranscriptStore store = TranscriptStore.open(temp, "cse_torn");
        store.append(List.of(message("evt_1", "hello")));
        store.close();
        Path file = Path.of(temp.toString(), "sessions", "cse_torn", "transcript.jsonl");
        Files.write(file, "{\"seq\":1,\"typ".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        TranscriptStore reopened = TranscriptStore.open(temp, "cse_torn");
        assertEquals(1, reopened.records().size());
        reopened.append(List.of(message("evt_2", "again")));

        assertEquals(2, reopened.records().size());
        assertEquals(1, reopened.records().get(1).seq());
        reopened.close();
    }

    @Test
    void comparesPrefixesByUuidAndPayload() {
        List<TranscriptStore.Record> shortLog = TranscriptStore.asRecords(List.of(message("evt_1", "a")));
        List<TranscriptStore.Record> longLog = TranscriptStore.asRecords(
                List.of(message("evt_1", "a"), message("evt_2", "b")));
        List<TranscriptStore.Record> diverged = TranscriptStore.asRecords(
                List.of(message("evt_1", "changed"), message("evt_2", "b")));

        assertTrue(TranscriptStore.isPrefix(shortLog, longLog));
        assertFalse(TranscriptStore.isPrefix(longLog, shortLog));
        assertFalse(TranscriptStore.isPrefix(diverged, longLog));
        assertTrue(TranscriptStore.isPrefix(List.of(), longLog));
    }

    @Test
    void treatsKeyOrderAsEqualWhenComparingPayloads() {
        JsonNode first = Json.parse("{\"type\":\"user\",\"uuid\":\"evt_1\",\"message\":{\"role\":\"user\"}}");
        JsonNode second = Json.parse("{\"message\":{\"role\":\"user\"},\"uuid\":\"evt_1\",\"type\":\"user\"}");

        assertTrue(TranscriptStore.isPrefix(
                TranscriptStore.asRecords(List.of(first)), TranscriptStore.asRecords(List.of(second))));
    }

    private static JsonNode message(String uuid, String text) {
        var payload = Json.object();
        payload.put("type", "user");
        payload.put("uuid", uuid);
        payload.putObject("message").put("role", "user").putArray("content")
                .addObject().put("type", "text").put("text", text);
        return payload;
    }
}
