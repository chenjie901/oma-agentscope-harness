package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OmaEventMapperTest {
    private static final String CODE_SESSION = "cse_ya2n925P7Ux8a9AwdwHq4vLw";

    private final OmaEventMapper mapper = new OmaEventMapper(CODE_SESSION);

    @Test
    void workerAssistantMessageCarriesEchoIdentity() {
        JsonNode payload = mapper.toOmaPayload(
                new CanonicalEvent.MessageCompleted("sthr_1", "oma_msg_1", "hello", "sevt_req_1"));

        assertEquals("agent.message", payload.get("type").asText());
        assertEquals("oma_msg_1", payload.get("id").asText());
        assertEquals("oma_msg_1", payload.get("uuid").asText());
        assertEquals("sevt_req_1", payload.get("model_request_start_id").asText());
        assertEquals("hello", payload.get("content").get(0).get("text").asText());
        assertFalse(payload.has("message"));
    }

    @Test
    void omitsRequestIdWhenItIsNotAServerEvent() {
        JsonNode payload = mapper.toOmaPayload(
                new CanonicalEvent.MessageCompleted("root", "oma_msg_2", "hello", "req_external"));

        assertFalse(payload.has("model_request_start_id"));
    }

    @Test
    void thinkingCarriesEchoIdentity() {
        JsonNode payload = mapper.toOmaPayload(
                new CanonicalEvent.ThinkingDelta("sthr_1", "oma_msg_3", "thinking", "sevt_req_2"));

        assertEquals("agent.thinking", payload.get("type").asText());
        assertEquals("sevt_req_2", payload.get("model_request_start_id").asText());
    }

    @Test
    void toolUseIdMatchesServerStableId() {
        JsonNode payload = mapper.toOmaPayload(new CanonicalEvent.ToolCallStarted(
                "root", "call_00_ktXJgjhHWTMBsAnUWkGK6235", "Bash", Json.object()));

        assertEquals("agent.tool_use", payload.get("type").asText());
        assertEquals("sevt_c04a61ba71c4153131401ec367cc0200", payload.get("id").asText());
    }

    @Test
    void toolResultReferencesSameStableId() {
        JsonNode started = mapper.toOmaPayload(new CanonicalEvent.ToolCallStarted(
                "root", "call_00_ktXJgjhHWTMBsAnUWkGK6235", "Bash", Json.object()));
        JsonNode completed = mapper.toOmaPayload(new CanonicalEvent.ToolCallCompleted(
                "root", "call_00_ktXJgjhHWTMBsAnUWkGK6235", "Bash", Json.object(),
                java.util.List.of(), false));

        assertEquals("agent.tool_result", completed.get("type").asText());
        assertEquals(started.get("id").asText(), completed.get("tool_use_id").asText());
    }

    @Test
    void toolEventIdIncludesThreadWhenPresent() {
        JsonNode payload = mapper.toOmaPayload(new CanonicalEvent.ToolCallStarted(
                "sthr_child", "call_1", "Bash", Json.object()));

        assertEquals("sthr_child", payload.get("thread_id").asText());
        assertEquals(37, payload.get("id").asText().length());
        assertFalse(payload.get("id").asText().equals(
                mapper.toOmaPayload(new CanonicalEvent.ToolCallStarted("root", "call_1", "Bash", Json.object()))
                        .get("id").asText()));
    }
}
