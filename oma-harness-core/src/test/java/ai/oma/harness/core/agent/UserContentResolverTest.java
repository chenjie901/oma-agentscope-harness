package ai.oma.harness.core.agent;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.attachments.AttachmentStore;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContentResolverTest {
    private HttpServer server;
    private OmaConfig config;
    private AttachmentStore attachments;

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
        WorkerTransport transport = new WorkerTransport(config);
        attachments = new AttachmentStore(transport, () -> 1L);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void resolvesTextAndBase64Image() {
        JsonNode payload = Json.parse("""
                {"message":{"content":[
                  {"type":"text","text":"look"},
                  {"type":"image","media_type":"image/png",
                   "source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}
                ]}}
                """);

        List<ContentBlock> blocks = UserContentResolver.resolve(payload, attachments);

        assertEquals(2, blocks.size());
        assertEquals("look", assertInstanceOf(TextBlock.class, blocks.get(0)).getText());
        Base64Source source = assertInstanceOf(Base64Source.class,
                assertInstanceOf(ImageBlock.class, blocks.get(1)).getSource());
        assertEquals("image/png", source.getMediaType());
        assertEquals("iVBORw0KGgo=", source.getData());
        assertEquals("look", UserContentResolver.extractText(payload));
    }

    @Test
    void resolvesFileImageByDownloadingAttachment() {
        byte[] png = new byte[] {1, 2, 3, 4};
        AtomicLong hit = new AtomicLong();
        server.createContext("/v1/code/sessions/cse_img/worker/message-attachments/file_1/content", exchange -> {
            hit.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        JsonNode payload = Json.parse("""
                {"message":{"content":[
                  {"type":"image","media_type":"image/jpeg",
                   "source":{"type":"file","file_id":"file_1"}}
                ]}}
                """);

        List<ContentBlock> blocks = UserContentResolver.resolve(payload, attachments);

        assertEquals(1, blocks.size());
        Base64Source source = assertInstanceOf(Base64Source.class,
                assertInstanceOf(ImageBlock.class, blocks.get(0)).getSource());
        assertEquals("image/jpeg", source.getMediaType());
        assertEquals(java.util.Base64.getEncoder().encodeToString(png), source.getData());
        assertEquals(1, hit.get());
        assertEquals("", UserContentResolver.extractText(payload));
    }

    @Test
    void resolvesUrlImage() {
        JsonNode payload = Json.parse("""
                {"message":{"content":[
                  {"type":"image","source":{"type":"url","url":"https://example.com/a.png","media_type":"image/png"}}
                ]}}
                """);

        List<ContentBlock> blocks = UserContentResolver.resolve(payload, attachments);

        assertEquals(1, blocks.size());
        URLSource source = assertInstanceOf(URLSource.class,
                assertInstanceOf(ImageBlock.class, blocks.get(0)).getSource());
        assertEquals("https://example.com/a.png", source.getUrl());
        assertEquals("image/png", source.getMimeType());
    }

    @Test
    void skipsImageWhenAttachmentMissing() {
        server.createContext("/v1/code/sessions/cse_img/worker/message-attachments/file_missing/content", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        JsonNode payload = Json.parse("""
                {"message":{"content":[
                  {"type":"text","text":"still here"},
                  {"type":"image","media_type":"image/png","source":{"type":"file","file_id":"file_missing"}}
                ]}}
                """);

        List<ContentBlock> blocks = UserContentResolver.resolve(payload, attachments);

        assertEquals(1, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
    }

    @Test
    void textualContentBecomesSingleTextBlock() {
        JsonNode payload = Json.parse("{\"message\":{\"content\":\"plain text\"}}");
        List<ContentBlock> blocks = UserContentResolver.resolve(payload, attachments);
        assertEquals(1, blocks.size());
        assertEquals("plain text", assertInstanceOf(TextBlock.class, blocks.get(0)).getText());
        assertEquals("plain text", UserContentResolver.extractText(payload));
    }
}
