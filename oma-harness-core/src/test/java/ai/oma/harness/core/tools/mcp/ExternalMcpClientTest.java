package ai.oma.harness.core.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalMcpClientTest {

    @Test
    void parsesPlainJsonRpcResponse() {
        JsonNode node = ExternalMcpClient.parseEnvelope(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertEquals("2025-03-26", node.get("result").get("protocolVersion").asText());
    }

    @Test
    void parsesSseDataLine() {
        JsonNode node = ExternalMcpClient.parseEnvelope(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n");
        assertEquals(0, node.get("result").get("tools").size());
    }

    @Test
    void skipsDoneSentinel() {
        JsonNode node = ExternalMcpClient.parseEnvelope("data: [DONE]\n");
        assertNull(node);
    }

    @Test
    void returnsNullForBlankOrGarbage() {
        assertNull(ExternalMcpClient.parseEnvelope(""));
        assertNull(ExternalMcpClient.parseEnvelope("not json"));
    }
}
