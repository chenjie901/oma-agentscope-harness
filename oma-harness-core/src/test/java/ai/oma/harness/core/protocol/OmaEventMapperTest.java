package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OmaEventMapperTest {

    @Test
    void workerAssistantMessageCarriesEchoIdentity() {
        JsonNode payload = new OmaEventMapper().toOmaPayload(
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
        JsonNode payload = new OmaEventMapper().toOmaPayload(
                new CanonicalEvent.MessageCompleted("root", "oma_msg_2", "hello", "req_external"));

        assertFalse(payload.has("model_request_start_id"));
    }

    @Test
    void thinkingCarriesEchoIdentity() {
        JsonNode payload = new OmaEventMapper().toOmaPayload(
                new CanonicalEvent.ThinkingDelta("sthr_1", "oma_msg_3", "thinking", "sevt_req_2"));

        assertEquals("agent.thinking", payload.get("type").asText());
        assertEquals("sevt_req_2", payload.get("model_request_start_id").asText());
    }
}
