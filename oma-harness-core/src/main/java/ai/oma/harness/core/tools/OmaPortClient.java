package ai.oma.harness.core.tools;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OmaPortClient {
    private static final Logger LOG = LoggerFactory.getLogger(OmaPortClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SessionHttp http;

    public OmaPortClient(OmaConfig config) {
        this.http = new SessionHttp(config);
    }

    public ToolResult call(int port, String threadId) {
        if (port < 1 || port > 65535) {
            return ToolResult.failure("port must be an integer between 1 and 65535");
        }
        ObjectNode body = Json.object();
        body.put("port", port);
        SessionHttp.Response response;
        try {
            response = http.post(http.sessionUri("port-url"), body, TIMEOUT, threadId);
        } catch (RuntimeException e) {
            return ToolResult.failure("OMA port URL service unavailable or timed out");
        }
        LOG.debug("port url response port={} status={} thread={}",
                port, response.status(), threadId);
        if (response.ok()) {
            JsonNode url = Json.nullable(response.body(), "url");
            if (url != null && url.isTextual()) {
                return ToolResult.text(url.asText());
            }
        }
        return ToolResult.failure("OMA port URL HTTP " + response.status() + ": " + errorMessage(response.body()));
    }

    private static String errorMessage(JsonNode body) {
        JsonNode error = body == null ? null : body.get("error");
        JsonNode message = error == null ? null : error.get("message");
        return message != null && message.isTextual() ? message.asText() : "Port URL request failed";
    }
}
