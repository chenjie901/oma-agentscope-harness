package ai.oma.harness.core.resources;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 资源加载器：从 OMA 服务端拉取会话可用的 skills / resources，注入到工具与 prompt 上下文。core 内部使用。 */
public final class OmaResourceLoader {
    private final OmaConfig config;
    private final HttpClient http;

    public OmaResourceLoader(OmaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public List<Skill> skills() {
        return skills(null);
    }

    public List<Skill> skills(String directory) {
        String base = config.apiBase().toString();
        String path = base + "v2/sessions/" + config.sessionId() + "/skills"
                + (directory == null || directory.isBlank() ? "" : "?directory=" + urlEncode(directory));
        JsonNode response = get(URI.create(path));
        List<Skill> skills = new ArrayList<>();
        for (JsonNode entry : Json.elements(response, "skills")) {
            skills.add(new Skill(
                    Json.text(entry, "name"),
                    Json.text(entry, "description"),
                    Json.text(entry, "path"),
                    Json.text(entry, "document"),
                    true,
                    "oma"));
        }
        return List.copyOf(skills);
    }

    private JsonNode get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("X-OMA-Session-ID", config.sessionId())
                .timeout(config.requestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new OmaHttpException(response.statusCode(), "resource_load_failed", response.body());
            }
            return Json.parse(response.body());
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "skill load failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "skill load interrupted");
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
