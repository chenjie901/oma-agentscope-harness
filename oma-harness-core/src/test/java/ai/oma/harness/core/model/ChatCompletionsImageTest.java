package ai.oma.harness.core.model;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatCompletionsImageTest {
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
    void sendsImageAsOpenAiImageUrlDataUrl() {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondDone(exchange);
        });

        Map<String, Object> user = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "describe"),
                Map.of("type", "image", "media_type", "image/png",
                        "source", Map.of("type", "base64", "media_type", "image/png", "data", "iVBORw0KGgo="))));
        ModelRequest request = new ModelRequest("model", null, List.of(user), List.of(), 1024, null, "main");

        new ChatCompletionsClient(config).send(request);

        JsonNode requestBody = Json.parse(body.get());
        JsonNode content = requestBody.get("messages").get(0).get("content");
        assertTrueArray(content);
        assertEquals("text", content.get(0).get("type").asText());
        JsonNode image = content.get(1);
        assertEquals("image_url", image.get("type").asText());
        assertEquals("data:image/png;base64,iVBORw0KGgo=", image.get("image_url").get("url").asText());
    }

    @Test
    void parsesImageUrlArrayContentInResponse() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"look\"},"
                    + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,AAAIMG==\"}}"
                    + "]},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Map<String, Object> user = Map.of("role", "user", "content", "draw");
        ModelRequest request = new ModelRequest("model", null, List.of(user), List.of(), 1024, null, "main");

        ModelReply reply = new ChatCompletionsClient(config).send(request);

        assertEquals("look", reply.text());
        Map<String, Object> image = reply.content().stream()
                .filter(b -> "image".equals(b.get("type")))
                .findFirst()
                .orElse(null);
        assertNotNull(image, "image_url block must be parsed into structured image content");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) image.get("source");
        assertEquals("base64", source.get("type"));
        assertEquals("image/jpeg", source.get("media_type"));
        assertEquals("AAAIMG==", source.get("data"));
    }

    private static void assertTrueArray(JsonNode node) {
        assertNotNull(node);
        org.junit.jupiter.api.Assertions.assertTrue(node.isArray());
    }

    private static void respondDone(HttpExchange exchange) throws IOException {
        byte[] response = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
