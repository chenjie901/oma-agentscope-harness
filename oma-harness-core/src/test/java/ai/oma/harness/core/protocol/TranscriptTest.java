package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptTest {

    @Test
    void writesUserPayloadWithUUID() {
        JsonNode payload = Transcript.user("evt_1", "hello");

        assertEquals("user", payload.get("type").asText());
        assertEquals("evt_1", payload.get("uuid").asText());
        assertEquals("user", payload.get("message").get("role").asText());
        assertEquals("hello", payload.get("message").get("content").get(0).get("text").asText());
    }

    @Test
    void generatesUUIDWhenMissing() {
        JsonNode payload = Transcript.assistant(null, "hi");

        assertTrue(payload.get("uuid").asText().startsWith("evt_"));
    }

    @Test
    void parsesSupportedPayloadsInOrder() {
        List<JsonNode> events = new ArrayList<>();
        events.add(wrap(Transcript.user("evt_1", "first")));
        events.add(wrap(Json.parse("""
                {"type":"dsh_log","uuid":"evt_2","line":"ignored"}
                """)));
        events.add(wrap(Transcript.assistant("evt_3", "second")));

        List<Transcript.RestoredMessage> messages = Transcript.parse(events);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("first", messages.get(0).text());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("second", messages.get(1).text());
    }

    @Test
    void skipsEmptyText() {
        List<JsonNode> events = List.of(wrap(Transcript.user("evt_1", "   ")));

        assertTrue(Transcript.parse(events).isEmpty());
    }

    @Test
    void readsTextFromStringContent() {
        List<JsonNode> events = List.of(wrap(Json.parse("""
                {"type":"user","uuid":"evt_1","message":{"role":"user","content":"plain"}}
                """)));

        assertEquals("plain", Transcript.parse(events).get(0).text());
    }

    private static JsonNode wrap(JsonNode payload) {
        var wrapper = Json.object();
        wrapper.set("payload", payload);
        return wrapper;
    }
}
