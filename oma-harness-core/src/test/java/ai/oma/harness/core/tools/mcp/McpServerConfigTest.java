package ai.oma.harness.core.tools.mcp;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConfigTest {

    @Test
    void parsesServersAndDisabledTools() {
        JsonNode config = Json.parse("{\"mcpServers\":{"
                + "\"github\":{\"type\":\"streamable-http\",\"url\":\"https://mcp.example.com/http\","
                + "\"headers\":{\"Authorization\":\"Bearer x\"},"
                + "\"tools\":[{\"name\":\"list_repos\",\"enabled\":false},{\"name\":\"get_repo\"}]}}}");
        List<McpServerConfig> servers = McpServerConfig.parse(config);
        assertEquals(1, servers.size());
        McpServerConfig github = servers.get(0);
        assertEquals("github", github.prefix());
        assertEquals(URI.create("https://mcp.example.com/http"), github.url());
        assertEquals("Bearer x", github.headers().get("Authorization"));
        assertTrue(github.disabledTools().contains("list_repos"));
        assertEquals("mcp__github__get_repo", github.toolName("get_repo"));
    }

    @Test
    void hashesComplexServerName() {
        assertEquals("simple_name", McpServerConfig.prefix("simple.name"));
        String complex = McpServerConfig.prefix("my server/with spaces");
        assertTrue(complex.startsWith("mcp_"));
        assertEquals(28, complex.length());
    }

    @Test
    void rejectsUnsupportedTransport() {
        JsonNode config = Json.parse("{\"mcpServers\":{\"bad\":{\"type\":\"stdio\",\"url\":\"https://x.test\"}}}");
        assertThrows(RuntimeException.class, () -> McpServerConfig.parse(config));
    }

    @Test
    void rejectsUrlWithUserInfo() {
        JsonNode config = Json.parse("{\"mcpServers\":{\"bad\":{\"type\":\"http\",\"url\":\"https://user@x.test/mcp\"}}}");
        assertThrows(RuntimeException.class, () -> McpServerConfig.parse(config));
    }

    @Test
    void rejectsAmbiguousPrefix() {
        JsonNode config = Json.parse("{\"mcpServers\":{"
                + "\"a.b\":{\"type\":\"http\",\"url\":\"https://x.test/1\"},"
                + "\"a_b\":{\"type\":\"http\",\"url\":\"https://x.test/2\"}}}");
        assertThrows(RuntimeException.class, () -> McpServerConfig.parse(config));
    }

    @Test
    void emptyConfigYieldsNoServers() {
        assertTrue(McpServerConfig.parse(null).isEmpty());
        assertTrue(McpServerConfig.parse(Json.object()).isEmpty());
    }
}
