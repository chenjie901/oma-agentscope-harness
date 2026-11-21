package ai.oma.harness.core.claim;

import ai.oma.harness.core.OmaConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextTest {
    private HttpServer server;
    private OmaConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        config = OmaConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .sessionId("cse_sig")
                .ingressToken("tok")
                .workerEpoch(1)
                .build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void signatureIsStableForSameContext() {
        server.createContext("/v2/sessions/cse_sig", exchange -> {
            byte[] body = sessionContext("instructions", "[\"Bash\"]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        String first = SessionContext.load(config).signature();
        String second = SessionContext.load(config).signature();

        assertEquals(first, second);
        assertEquals(64, first.length(), "signature must be a SHA-256 hex digest");
    }

    @Test
    void signatureChangesWhenPromptOrToolsOrSkillsChange() {
        String baseline = signatureOf("instructions", "[\"Bash\"]", "[]");
        assertNotEquals(baseline, signatureOf("changed instructions", "[\"Bash\"]", "[]"));
        assertNotEquals(baseline, signatureOf("instructions", "[\"Bash\",\"Read\"]", "[]"));
        assertNotEquals(baseline, signatureOf("instructions", "[\"Bash\"]", "[{\"name\":\"sk\"}]"));
    }

    @Test
    void runtimeSkillsAreParsed() {
        server.createContext("/v2/sessions/cse_sig", exchange -> {
            byte[] body = sessionContext("instructions", "[]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        SessionContext session = SessionContext.load(config);
        assertTrue(session.runtimeSkills() != null);
        assertTrue(session.runtimeSkills().isArray());
    }

    private final AtomicReference<String> response = new AtomicReference<>();

    private void respondWith(String body) {
        response.set(body);
    }

    private void installDynamicHandler() {
        server.createContext("/v2/sessions/cse_sig", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private String signatureOf(String instructions, String tools, String runtimeSkills) {
        if (response.get() == null) {
            installDynamicHandler();
        }
        respondWith(sessionContext(instructions, tools, runtimeSkills));
        return SessionContext.load(config).signature();
    }

    private String sessionContext(String instructions, String tools) {
        return sessionContext(instructions, tools, "[]");
    }

    private String sessionContext(String instructions, String tools, String runtimeSkills) {
        return "{\"session_context\":{\"model\":\"m\",\"cwd\":\"/work\","
                + "\"system_prompt\":\"" + instructions + "\","
                + "\"runtime_tools\":" + tools + ","
                + "\"runtime_skills\":" + runtimeSkills + "}}";
    }
}
