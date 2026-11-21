package ai.oma.harness.core.agentscope;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OmaChatModelImageTest {
    private HttpServer server;
    private OmaConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        config = OmaConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .sessionId("cse_img")
                .ingressToken("token")
                .workerEpoch(1)
                .build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void messagesBackendSendsAnthropicImageBlock() {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondMessages(exchange);
        });

        Msg user = new UserMessage(List.of(
                io.agentscope.core.message.TextBlock.builder().text("describe").build(),
                new ImageBlock(new Base64Source("image/png", "iVBORw0KGgo="))));

        new OmaChatModel(config, "model", SessionContext.BACKEND_MESSAGES)
                .stream(List.of(user), List.<ToolSchema>of(), null)
                .blockLast();

        JsonNode request = Json.parse(body.get());
        JsonNode content = request.get("messages").get(0).get("content");
        assertEquals("text", content.get(0).get("type").asText());
        JsonNode image = content.get(1);
        assertEquals("image", image.get("type").asText());
        assertEquals("base64", image.get("source").get("type").asText());
        assertEquals("image/png", image.get("source").get("media_type").asText());
        assertEquals("iVBORw0KGgo=", image.get("source").get("data").asText());
    }

    @Test
    void messagesBackendOutputsImageBlockInChatResponse() {
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {"id":"msg_1","type":"message","role":"assistant","model":"m",
                     "content":[{"type":"text","text":"here"},
                                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"OUTPNG=="}}],
                     "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Msg user = new UserMessage(io.agentscope.core.message.TextBlock.builder().text("draw").build());
        java.util.List<io.agentscope.core.model.ChatResponse> responses =
                new OmaChatModel(config, "model", SessionContext.BACKEND_MESSAGES)
                        .stream(List.of(user), List.<ToolSchema>of(), null)
                        .collectList()
                        .block();

        assertNotNull(responses);
        ImageBlock image = responses.stream()
                .flatMap(r -> r.getContent().stream())
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(image, "model output image must survive into ChatResponse content");
        Base64Source source = (Base64Source) image.getSource();
        assertEquals("image/png", source.getMediaType());
        assertEquals("OUTPNG==", source.getData());
    }

    private static void respondMessages(HttpExchange exchange) throws IOException {
        byte[] response = """
                {"id":"msg_1","type":"message","role":"assistant","model":"m",
                 "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
