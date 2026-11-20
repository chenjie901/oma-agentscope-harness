package ai.oma.harness.core.tools;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmaWebClientTest {
    private HttpServer server;
    private OmaConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        config = OmaConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .sessionId("cse_web")
                .ingressToken("token")
                .workerEpoch(1)
                .build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void storesFullContentAndReturnsOutputFile() {
        AtomicReference<String> saved = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        respond("/v2/ccr-sessions/cse_web/web/fetch", 200, """
                {"url":"https://example.com","title":"Example","content":"preview","full_content":"FULL TEXT"}
                """, thread);

        ToolResult result = new OmaWebClient(config, (content, threadId) -> {
            saved.set(content);
            return "/tmp/tool-outputs/mcp_test.txt";
        }).call("fetch", Json.parse("{\"url\":\"https://example.com\"}"), "child-1");

        assertFalse(result.error());
        JsonNode page = Json.parse(result.text());
        assertEquals("/tmp/tool-outputs/mcp_test.txt", page.get("output_file").asText());
        assertEquals("preview", page.get("content").asText());
        assertFalse(page.has("full_content"));
        assertEquals("child-1", thread.get());

        JsonNode stored = Json.parse(saved.get());
        assertEquals("FULL TEXT", stored.get("content").asText());
        assertFalse(stored.get("truncated").asBoolean(true));
    }

    @Test
    void degradesToPreviewWhenStorageFails() {
        respond("/v2/ccr-sessions/cse_web/web/search", 200, """
                {"query":"oma","full_content":"FULL TEXT"}
                """, new AtomicReference<>());

        ToolResult result = new OmaWebClient(config, (content, threadId) -> {
            throw new IllegalStateException("sandbox unavailable");
        }).call("search", Json.parse("{\"query\":\"oma\"}"), "root");

        JsonNode page = Json.parse(result.text());
        assertFalse(page.has("output_file"));
        assertEquals(OmaWebClient.STORAGE_ERROR, page.get("storage_error").asText());
    }

    @Test
    void reportsHttpErrorWithServerMessage() {
        respond("/v2/ccr-sessions/cse_web/web/fetch", 403, """
                {"error":{"message":"url_not_allowed"}}
                """, new AtomicReference<>());

        ToolResult result = new OmaWebClient(config, (content, threadId) -> "/tmp/file.txt")
                .call("fetch", Json.parse("{\"url\":\"https://blocked.example\"}"), "root");

        assertTrue(result.error());
        assertEquals("OMA web tool HTTP 403: url_not_allowed", result.text());
    }

    @Test
    void returnsPortUrlAndValidatesRange() {
        respond("/v2/ccr-sessions/cse_web/port-url", 200, "{\"url\":\"https://3000-session.example\"}",
                new AtomicReference<>());
        OmaPortClient client = new OmaPortClient(config);

        ToolResult result = client.call(3000, "root");
        ToolResult invalid = client.call(0, "root");

        assertEquals("https://3000-session.example", result.text());
        assertTrue(result.error() == false);
        assertTrue(invalid.error());
        assertEquals("port must be an integer between 1 and 65535", invalid.text());
    }

    @Test
    void reportsPortHttpError() {
        respond("/v2/ccr-sessions/cse_web/port-url", 403, """
                {"error":{"message":"get_port_url is not enabled for this Agent"}}
                """, new AtomicReference<>());

        ToolResult result = new OmaPortClient(config).call(8081, "root");

        assertEquals("OMA port URL HTTP 403: get_port_url is not enabled for this Agent", result.text());
    }

    private void respond(String path, int status, String body, AtomicReference<String> threadHeader) {
        server.createContext(path, exchange -> {
            threadHeader.set(exchange.getRequestHeaders().getFirst("X-OMA-Session-Thread-ID"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
