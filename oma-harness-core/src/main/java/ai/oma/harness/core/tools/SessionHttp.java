package ai.oma.harness.core.tools;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class SessionHttp {
    record Response(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final OmaConfig config;
    private final HttpClient http;

    SessionHttp(OmaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    URI sessionUri(String suffix) {
        return URI.create(config.sessionBase().toString() + "/" + suffix);
    }

    Response post(URI uri, JsonNode body, Duration timeout, String threadId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("X-OMA-Session-ID", config.sessionId())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (threadId != null && !threadId.isBlank() && !"root".equals(threadId)) {
            builder.header("X-OMA-Session-Thread-ID", threadId);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = response.body();
            return new Response(response.statusCode(),
                    text == null || text.isBlank() ? Json.object() : Json.parse(text));
        } catch (IOException e) {
            throw new IllegalStateException("session request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("session request interrupted", e);
        }
    }
}
