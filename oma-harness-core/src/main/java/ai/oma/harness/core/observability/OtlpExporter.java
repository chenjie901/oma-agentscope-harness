package ai.oma.harness.core.observability;

import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.LongSupplier;

public final class OtlpExporter {
    private static final Logger LOG = LoggerFactory.getLogger(OtlpExporter.class);
    private static final Set<String> KINDS = Set.of("logs", "traces");
    private static final String CONTENT_TYPE = "application/json";

    private final WorkerTransport transport;
    private final LongSupplier epoch;
    private final boolean enabled;

    public OtlpExporter(WorkerTransport transport, LongSupplier epoch, boolean enabled) {
        this.transport = transport;
        this.epoch = epoch;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void export(String kind, byte[] body) {
        if (!enabled || kind == null || !KINDS.contains(kind) || body == null || body.length == 0) {
            return;
        }
        if (body.length > 8 * 1024 * 1024) {
            LOG.debug("otlp batch too large, dropped kind={} bytes={}", kind, body.length);
            return;
        }
        try {
            JsonNode response = transport.sendOtlp(epoch.getAsLong(), kind, body, CONTENT_TYPE);
            JsonNode partial = response == null ? null : response.get("partialSuccess");
            if (partial != null && !partial.isNull() && partial.path("rejectedLogRecords").asLong(0) > 0) {
                LOG.debug("otlp partial rejection kind={} rejected={}",
                        kind, partial.path("rejectedLogRecords").asLong(0));
            }
        } catch (RuntimeException e) {
            LOG.debug("otlp export dropped kind={} error={}", kind, e.getMessage());
        }
    }

    public void exportJson(String kind, String json) {
        if (json == null) {
            return;
        }
        export(kind, json.getBytes(StandardCharsets.UTF_8));
    }
}
