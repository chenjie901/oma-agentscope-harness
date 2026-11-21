package ai.oma.harness.core.observability;

import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** 诊断指标上报：把 worker 的运行状态与异常以 diagnostics 事件写入本地日志与服务端。core 内部使用。 */
public final class DiagnosticsReporter {
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticsReporter.class);
    private static final Set<String> ALLOWED_KINDS = Set.of(
            "oma_runtime_event", "oma_model_request_retry", "oma_runtime_input_failed");

    private final WorkerTransport transport;
    private final LongSupplier epoch;
    private final boolean enabled;

    public DiagnosticsReporter(WorkerTransport transport, LongSupplier epoch, boolean enabled) {
        this.transport = transport;
        this.epoch = epoch;
        this.enabled = enabled;
    }

    public void event(String kind, String codeLocation) {
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            return;
        }
        ObjectNode line = Json.object();
        line.put("type", "env_manager_log");
        line.put("uuid", java.util.UUID.randomUUID().toString());
        ObjectNode data = line.putObject("data");
        data.put("type", kind);
        if (codeLocation != null && codeLocation.length() <= 200) {
            data.put("code", codeLocation);
        }
        LOG.debug("diagnostic kind={} code={}", kind, codeLocation);
        if (!enabled) {
            return;
        }
        try {
            transport.reportDiagnostics(epoch.getAsLong(), List.of(line));
        } catch (RuntimeException e) {
            LOG.debug("diagnostic report failed: {}", e.getMessage());
        }
    }
}
