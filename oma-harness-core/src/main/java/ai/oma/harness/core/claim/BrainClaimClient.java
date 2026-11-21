package ai.oma.harness.core.claim;

import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * brain claim 协议客户端：向 OMA 服务端 claim 一个待处理会话（长轮询），并在退出时释放。
 * 由 OmaWorker 使用，接入方无需直接接触。
 */
public final class BrainClaimClient {
    private static final long MAX_BLOCK_MS = 30_000;

    private final HttpClient http;
    private final URI endpoint;
    private final String claimToken;
    private final String workerId;

    public BrainClaimClient(URI endpoint, String claimToken, String workerId) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.endpoint = endpoint.toString().endsWith("/")
                ? URI.create(endpoint.toString().substring(0, endpoint.toString().length() - 1))
                : endpoint;
        this.claimToken = claimToken;
        this.workerId = workerId;
    }

    public ClaimedSession claim(long blockMs) {
        long effectiveBlock = Math.max(0, Math.min(blockMs, MAX_BLOCK_MS));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/v1/code/brain/claims?block_ms=" + effectiveBlock))
                .header("Authorization", "Bearer " + claimToken)
                .header("X-Worker-ID", workerId)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(effectiveBlock + 15_000))
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 204) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new OmaHttpException(response.statusCode(), "claim_failed", response.body());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "brain claim failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "brain claim interrupted");
        }
    }

    public void release(ClaimedSession claim, String reason) {
        ObjectNode body = Json.object();
        body.put("worker_epoch", claim.workerEpoch());
        body.put("reason", reason);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        endpoint + "/v1/code/brain/claims/" + claim.sessionId() + "/release"))
                .header("Authorization", "Bearer " + claimToken)
                .header("X-Worker-ID", workerId)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                throw new OmaHttpException(response.statusCode(), "release_failed", response.body());
            }
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "brain claim release failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "brain claim release interrupted");
        }
    }

    private ClaimedSession parse(String body) {
        JsonNode json = Json.parse(body);
        String sessionId = Json.text(json, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new OmaHttpException(200, "invalid_claim", "claim response has no session_id");
        }
        return new ClaimedSession(
                sessionId,
                Json.longValue(json, "worker_epoch", 0),
                Json.text(json, "ingress_token"),
                Json.text(json, "model_token"),
                Json.text(json, "claim_expires_at"),
                Json.text(json, "trace_parent"));
    }
}
