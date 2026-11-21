package ai.oma.harness.core.tools;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OMA web 工具客户端：向服务端发起网页抓取/搜索请求，供 OmaToolkit 注册为工具。core 内部使用。 */
public final class OmaWebClient {
    private static final Logger LOG = LoggerFactory.getLogger(OmaWebClient.class);
    public static final String STORAGE_ERROR = "Full content could not be saved; only the preview is available.";
    private static final Duration TIMEOUT = Duration.ofSeconds(35);

    public interface OutputWriter {
        String write(String content, String threadId);
    }

    private final SessionHttp http;
    private final OutputWriter writer;

    public OmaWebClient(OmaConfig config, OutputWriter writer) {
        this.http = new SessionHttp(config);
        this.writer = writer;
    }

    public ToolResult call(String remoteName, JsonNode input, String threadId) {
        SessionHttp.Response response;
        try {
            response = http.post(http.sessionUri("web/" + remoteName), input, TIMEOUT, threadId);
        } catch (RuntimeException e) {
            return ToolResult.failure("OMA web tool unavailable or timed out");
        }
        LOG.debug("web tool response tool={} status={} thread={}",
                remoteName, response.status(), threadId);
        if (!response.ok()) {
            return ToolResult.failure("OMA web tool HTTP " + response.status() + ": " + errorMessage(response.body()));
        }
        JsonNode fullContent = Json.nullable(response.body(), "full_content");
        if (fullContent == null || !fullContent.isTextual()) {
            return ToolResult.text(Json.write(response.body()));
        }
        ObjectNode page = response.body().deepCopy();
        page.remove("full_content");
        ObjectNode stored = page.deepCopy();
        stored.put("content", fullContent.asText());
        stored.put("truncated", false);
        try {
            String path = writer.write(Json.write(stored), threadId);
            page.put("output_file", path);
            LOG.info("web full_content stored tool={} bytes={} path={}",
                    remoteName, stored.toString().length(), path);
        } catch (RuntimeException e) {
            page.put("storage_error", STORAGE_ERROR);
            LOG.warn("web full_content storage failed tool={}: {}",
                    remoteName, e.getMessage());
        }
        return ToolResult.text(Json.write(page));
    }

    private static String errorMessage(JsonNode body) {
        JsonNode error = body == null ? null : body.get("error");
        JsonNode message = error == null ? null : error.get("message");
        return message != null && message.isTextual() ? message.asText() : "Web tool request failed";
    }
}
