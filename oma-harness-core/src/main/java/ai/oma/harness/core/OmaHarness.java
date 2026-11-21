package ai.oma.harness.core;

import ai.oma.harness.core.agent.AgentContext;
import ai.oma.harness.core.agent.HarnessAgent;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.model.OmaModelClient;
import ai.oma.harness.core.persistence.InternalEventStore;
import ai.oma.harness.core.persistence.TranscriptStore;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.protocol.OmaEventMapper;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.protocol.WorkerMetadata;
import ai.oma.harness.core.resources.OmaResourceLoader;
import ai.oma.harness.core.resources.Skill;
import ai.oma.harness.core.tools.OmaPortClient;
import ai.oma.harness.core.tools.OmaToolClient;
import ai.oma.harness.core.tools.OmaWebClient;
import ai.oma.harness.core.tools.ToolResult;
import ai.oma.harness.core.tools.mcp.ExternalMcpRegistry;
import ai.oma.harness.core.tools.mcp.McpToolBinding;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.observability.DiagnosticsReporter;
import ai.oma.harness.core.observability.OtlpExporter;
import ai.oma.harness.core.observability.SessionMdc;
import ai.oma.harness.core.attachments.AttachmentStore;
import ai.oma.harness.core.transport.InboundEvent;
import ai.oma.harness.core.transport.OmaHttpException;
import ai.oma.harness.core.transport.SseParser;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个会话的运行时：实现 AgentContext，把 agent 产生的 canonical 事件转换为 OMA wire 事件上报，
 * 并管理 transcript、内部事件、附件、控制面与运行时工具刷新。由 OmaWorker 创建并注入 agent，
 * 接入方无需直接接触。
 */
public final class OmaHarness implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OmaHarness.class);
    private static final int INTERNAL_EVENT_BATCH = 50;
    private static final long PROCESSING_RENEWAL_SECONDS = 60;

    private final OmaConfig config;
    private final HarnessAgent agent;
    private final OmaEventMapper mapper;
    private final WorkerTransport transport;
    private final OmaToolClient tools;
    private final OmaWebClient web;
    private final OmaPortClient ports;
    private final OmaModelClient models;
    private final OmaResourceLoader resources;
    private final ExternalMcpRegistry mcpRegistry;
    private final DiagnosticsReporter diagnostics;
    private final OtlpExporter otlp;
    private final AttachmentStore attachments;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService turns;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger heartbeatFailures = new AtomicInteger();
    private final Map<String, ScheduledFuture<?>> processingRenewals = new ConcurrentHashMap<>();
    private volatile boolean busy;
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastEventNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastSequenceNum = new AtomicLong(0);
    private volatile long epoch;
    private volatile WorkerTransport.StreamHandle activeStream;
    private volatile InternalEventStore internalEvents;
    private volatile TranscriptStore transcript;
    private volatile String threadId = "root";
    private volatile ControlPlane controlPlane;
    private volatile List<Transcript.RestoredMessage> restoredMessages = List.of();

    private OmaHarness(OmaConfig config, HarnessAgent agent) {
        this.config = config;
        this.agent = agent;
        this.epoch = config.workerEpoch();
        this.mapper = new OmaEventMapper(config.sessionId());
        this.transport = new WorkerTransport(config);
        this.tools = new OmaToolClient(config);
        this.web = new OmaWebClient(config, this::saveSandboxOutput);
        this.ports = new OmaPortClient(config);
        this.models = new OmaModelClient(config);
        this.resources = new OmaResourceLoader(config);
        this.mcpRegistry = new ExternalMcpRegistry(config);
        boolean observability = Boolean.parseBoolean(
                System.getenv().getOrDefault("OMA_OBSERVABILITY_ENABLED", "false"));
        this.diagnostics = new DiagnosticsReporter(transport, () -> epoch, observability);
        this.otlp = new OtlpExporter(transport, () -> epoch, observability);
        this.attachments = new AttachmentStore(transport, () -> epoch);
        this.scheduler = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "oma-harness-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.turns = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "oma-harness-turn");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public void run() {
        Map<String, String> previousMdc = SessionMdc.captureAndPut(config.sessionId());
        try {
            runSession();
        } finally {
            SessionMdc.restore(previousMdc);
        }
    }

    private void runSession() {
        long reconnectStartedAt = System.nanoTime();
        long backoffMillis = 500;
        epoch = transport.register();
        LOG.info("worker registered epoch={} session={}", epoch, config.sessionId());
        recover();
        try {
            agent.start(new Context());
        } catch (Exception e) {
            throw new IllegalStateException("harness failed to start", e);
        }
        scheduler.scheduleWithFixedDelay(SessionMdc.wrap(config.sessionId(), this::heartbeatSafely),
                config.heartbeatInterval().toMillis(), config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(SessionMdc.wrap(config.sessionId(), this::watchdog),
                config.streamIdleTimeout().toMillis(), 1000, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(SessionMdc.wrap(config.sessionId(), this::keepAliveSafely),
                30_000, 30_000, TimeUnit.MILLISECONDS);
        heartbeatSafely();
        while (!closed.get()) {
            try {
                streamOnce();
                reconnectStartedAt = System.nanoTime();
                backoffMillis = 500;
            } catch (OmaHttpException e) {
                if (e.epochTerminal()) {
                    LOG.warn("worker epoch is no longer valid: {}", e.getMessage());
                    return;
                }
                if (closed.get()) {
                    return;
                }
                long elapsedMillis = (System.nanoTime() - reconnectStartedAt) / 1_000_000L;
                if (elapsedMillis > config.reconnectBudget().toMillis()) {
                    throw new IllegalStateException("worker stream reconnection budget exhausted", e);
                }
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, config.maxReconnectBackoff().toMillis());
            }
        }
    }

    private void recover() {
        WorkerMetadata metadata = WorkerMetadata.empty();
        try {
            metadata = WorkerMetadata.parse(transport.getWorker(epoch));
        } catch (OmaHttpException e) {
            LOG.warn("worker metadata read failed: {}", e.getMessage());
        }
        TranscriptStore local = TranscriptStore.open(config.sessionId());
        InternalEventStore store = new InternalEventStore(transport, () -> epoch, INTERNAL_EVENT_BATCH, local);
        List<JsonNode> history = store.synchronize(false);
        LOG.debug("transcript synchronized local={} remote_and_local={} file={}",
                local.records().size(), history.size(), local.file());
        transcript = local;
        try {
            restoredMessages = Transcript.parse(history);
        } catch (RuntimeException e) {
            LOG.warn("internal event restore failed: {}", e.getMessage());
        }
        internalEvents = store;
        controlPlane = new ControlPlane(new ControlSink(), metadata);
        if (!restoredMessages.isEmpty()) {
            LOG.info("restored {} transcript messages for {}",
                    restoredMessages.size(), config.sessionId());
        }
    }

    private void streamOnce() {
        lastActivityNanos.set(System.nanoTime());
        LOG.debug("opening worker stream from sequence={} epoch={}",
                lastSequenceNum.get(), epoch);
        try (WorkerTransport.StreamHandle handle = transport.openStream(epoch, lastSequenceNum.get())) {
            activeStream = handle;
            BufferedReader reader = new BufferedReader(new InputStreamReader(handle.body(), StandardCharsets.UTF_8));
            SseParser parser = new SseParser(reader, sequence -> lastActivityNanos.set(System.nanoTime()));
            SseParser.SseFrame frame;
            while (!closed.get() && (frame = parser.next()) != null) {
                if (!frame.isClientEvent()) {
                    continue;
                }
                InboundEvent event = toInboundEvent(frame);
                LOG.debug("worker event seq={} type={} id={}",
                        event.sequenceNum(), event.eventType(), event.eventId());
                if (event.sequenceNum() > 0) {
                    lastSequenceNum.set(Math.max(lastSequenceNum.get(), event.sequenceNum()));
                }
                dispatch(event);
            }
        } catch (java.io.IOException e) {
            throw new OmaHttpException(0, "stream_closed", "worker stream closed: " + e.getMessage());
        } finally {
            activeStream = null;
        }
    }

    private InboundEvent toInboundEvent(SseParser.SseFrame frame) {
        JsonNode json = frame.json();
        String eventType = canonicalEventType(Json.text(json, "event_type"), json.get("payload"));
        String eventId = Json.text(json, "event_id");
        JsonNode payload = json.get("payload");
        if (eventId == null || eventId.isBlank()) {
            eventId = payload == null ? null : Json.text(payload, "uuid");
        }
        return new InboundEvent(
                frame.sequenceNum(),
                eventId,
                eventType,
                Json.text(json, "event_subtype"),
                Json.text(json, "session_id"),
                payload,
                json);
    }

    private static String canonicalEventType(String wireType, JsonNode payload) {
        String value = wireType == null ? "" : wireType;
        if ("user".equals(value)) {
            return "user.message";
        }
        if ("control_request".equals(value)) {
            JsonNode request = payload == null ? null : payload.get("request");
            String subtype = request == null ? null : Json.text(request, "subtype");
            if ("interrupt".equals(subtype)) {
                return "user.interrupt";
            }
        }
        return value;
    }

    private void dispatch(InboundEvent event) {
        LOG.info("dispatch event type={} id={} thread={}",
                event.eventType(), event.eventId(), threadId);
        if ("user.interrupt".equals(event.eventType())) {
            deliverInterrupt(event);
            return;
        }
        if (isControlEvent(event)) {
            deliverControl(event);
            return;
        }
        try {
            turns.execute(SessionMdc.wrap(config.sessionId(), () -> deliver(event)));
        } catch (RejectedExecutionException e) {
            LOG.debug("harness closed before event dispatch: {}", e.getMessage());
        }
    }

    private boolean isControlEvent(InboundEvent event) {
        ControlPlane plane = controlPlane;
        JsonNode payload = event.payload();
        return plane != null && payload != null && plane.handles(payload) && !ControlPlane.isInterrupt(payload);
    }

    private void deliverControl(InboundEvent event) {
        acknowledge(event, "received");
        lastEventNanos.set(System.nanoTime());
        try {
            controlPlane.handle(event.payload());
            acknowledge(event, "processed");
        } catch (RuntimeException e) {
            LOG.warn("harness failed to handle control event " + event.eventId(), e);
        } finally {
            lastActivityNanos.set(System.nanoTime());
        }
    }

    private void deliverInterrupt(InboundEvent event) {
        cancelled.set(true);
        ControlPlane plane = controlPlane;
        if (plane != null) {
            plane.interruptApprovals("Tool call interrupted.");
        }
        acknowledge(event, "received");
        lastEventNanos.set(System.nanoTime());
        try {
            agent.deliver(event);
            acknowledge(event, "processed");
        } catch (Exception e) {
            LOG.warn("harness failed to interrupt event " + event.eventId(), e);
        } finally {
            lastActivityNanos.set(System.nanoTime());
        }
    }

    private void deliver(InboundEvent event) {
        adoptThread(event.payload());
        acknowledge(event, "received");
        beginProcessing(event);
        lastEventNanos.set(System.nanoTime());
        busy = true;
        long startedAt = System.nanoTime();
        try {
            agent.deliver(event);
            cancelled.set(false);
            acknowledge(event, "processed");
            flushInternalEvents();
            emitTurnResult(startedAt);
        } catch (Exception e) {
            LOG.warn("harness failed to handle event " + event.eventId(), e);
            diagnostics.event("oma_runtime_event", "OmaHarness.deliver");
            emitTurnFailure(startedAt);
            InternalEventStore store = internalEvents;
            if (store != null && store.isTerminal()) {
                close();
            }
        } finally {
            endProcessing(event);
            busy = false;
            lastActivityNanos.set(System.nanoTime());
        }
    }

    private void adoptThread(JsonNode payload) {
        String thread = Json.text(payload, "session_thread_id");
        if (thread != null && !thread.isBlank()) {
            threadId = thread;
        }
    }

    private void beginProcessing(InboundEvent event) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        acknowledge(event, "processing");
        ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(
                SessionMdc.wrap(config.sessionId(), () -> acknowledge(event, "processing")), PROCESSING_RENEWAL_SECONDS,
                PROCESSING_RENEWAL_SECONDS, TimeUnit.SECONDS);
        processingRenewals.put(eventId, renewal);
    }

    private void endProcessing(InboundEvent event) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        ScheduledFuture<?> renewal = processingRenewals.remove(eventId);
        if (renewal != null) {
            renewal.cancel(false);
        }
    }

    private void flushInternalEvents() {
        InternalEventStore store = internalEvents;
        if (store != null) {
            store.flush();
        }
    }

    private void emitTurnResult(long startedAt) {
        long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
        JsonNode payload = mapper.turnResultPayload("success", false, durationMs);
        LOG.debug("turn result emit duration_ms={} thread={}", durationMs, threadId);
        emitResult(payload);
    }

    private void emitTurnFailure(long startedAt) {
        long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
        JsonNode payload = mapper.turnResultPayload("error_max_turns", true, durationMs);
        LOG.debug("turn failure emit duration_ms={} thread={}", durationMs, threadId);
        emitResult(payload);
    }

    private void emitResult(JsonNode payload) {
        try {
            transport.emit(epoch, List.of(new WorkerTransport.OutboundEvent(payload, false)));
        } catch (OmaHttpException e) {
            LOG.warn("turn result rejected: {}", e.getMessage());
        }
    }

    public boolean idleFor(Duration threshold) {
        return !busy && System.nanoTime() - lastEventNanos.get() > threshold.toNanos();
    }

    private String saveSandboxOutput(String content, String threadId) {
        String path = "/tmp/tool-outputs/mcp_" + UUID.randomUUID() + ".txt";
        ObjectNode input = Json.object();
        input.put("file_path", path);
        input.put("content", content);
        ToolResult result = tools.call("write", input, Duration.ofMinutes(2), threadId);
        if (result.error()) {
            throw new IllegalStateException("sandbox output persistence failed");
        }
        return path;
    }

    private void acknowledge(InboundEvent event, String status) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            return;
        }
        LOG.debug("delivery update event={} type={} status={} thread={}",
                event.eventId(), event.eventType(), status, threadId);
        try {
            transport.acknowledge(epoch, List.of(new WorkerTransport.DeliveryUpdate(event.eventId(), status)));
        } catch (OmaHttpException e) {
            LOG.debug("delivery update rejected: {}", e.getMessage());
        }
    }

    private void keepAliveSafely() {
        if (closed.get() || activeStream == null) {
            return;
        }
        ObjectNode payload = Json.object();
        payload.put("type", "keep_alive");
        payload.put("uuid", java.util.UUID.randomUUID().toString());
        try {
            transport.emit(epoch, List.of(new WorkerTransport.OutboundEvent(payload, true)));
            LOG.debug("keep_alive emitted epoch={}", epoch);
        } catch (OmaHttpException e) {
            LOG.debug("keep_alive rejected: {}", e.getMessage());
        }
    }

    private void heartbeatSafely() {
        if (closed.get()) {
            return;
        }
        try {
            transport.heartbeat(epoch);
            heartbeatFailures.set(0);
        } catch (OmaHttpException e) {
            if (e.epochTerminal()) {
                LOG.warn("heartbeat rejected, closing: {}", e.getMessage());
                close();
                return;
            }
            heartbeatFailed("heartbeat failed: " + e.getMessage());
        } catch (RuntimeException e) {
            heartbeatFailed("heartbeat failed: " + e.getMessage());
        }
    }

    private void heartbeatFailed(String message) {
        int failures = heartbeatFailures.incrementAndGet();
        LOG.warn(message);
        if (failures >= 3) {
            LOG.warn("heartbeat failed {} times, releasing session", failures);
            close();
        }
    }

    private void watchdog() {
        if (closed.get()) {
            return;
        }
        long idleMillis = (System.nanoTime() - lastActivityNanos.get()) / 1_000_000L;
        if (idleMillis <= config.streamIdleTimeout().toMillis()) {
            return;
        }
        WorkerTransport.StreamHandle handle = activeStream;
        if (handle != null) {
            LOG.debug("worker stream idle for {}ms, closing", idleMillis);
            handle.close();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed.set(true);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WorkerTransport.StreamHandle handle = activeStream;
        if (handle != null) {
            handle.close();
        }
        for (ScheduledFuture<?> renewal : processingRenewals.values()) {
            renewal.cancel(false);
        }
        processingRenewals.clear();
        scheduler.shutdownNow();
        turns.shutdownNow();
        TranscriptStore store = transcript;
        if (store != null) {
            store.close();
        }
        mcpRegistry.close();
        try {
            agent.close();
        } catch (Exception e) {
            LOG.debug("harness close failed", e);
        }
    }

    private final class Context implements AgentContext {
        private volatile List<Skill> cachedSkills;

        @Override
        public String sessionId() {
            return config.sessionId();
        }

        @Override
        public String threadId() {
            return threadId;
        }

        @Override
        public void emit(CanonicalEvent event) {
            LOG.debug("emit canonical event={} thread={}", event.getClass().getSimpleName(), threadId);
            if (event instanceof CanonicalEvent.TurnStarted || event instanceof CanonicalEvent.SessionRunning) {
                publishStatus("running");
                return;
            }
            if (event instanceof CanonicalEvent.TurnCompleted || event instanceof CanonicalEvent.SessionIdle) {
                publishStatus("idle");
                return;
            }
            emitNative(mapper.toOmaPayload(event));
        }

        @Override
        public void emitNative(JsonNode payload) {
            try {
                boolean ephemeral = OmaEventMapper.isEphemeral(payload);
                LOG.debug("emit native type={} ephemeral={} thread={}", Json.text(payload, "type"), ephemeral, threadId);
                transport.emit(epoch, List.of(new WorkerTransport.OutboundEvent(payload, ephemeral)));
            } catch (OmaHttpException e) {
                LOG.warn("emit rejected: {}", e.getMessage());
            }
        }

        private void publishStatus(String status) {
            LOG.debug("publish worker status={} thread={}", status, threadId);
            try {
                transport.setWorkerStatus(epoch, status, null, null);
            } catch (OmaHttpException e) {
                LOG.warn("worker status update rejected: {}", e.getMessage());
            }
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout) {
            return tools.call(tool, input, timeout, config.threadId());
        }

        @Override
        public ToolResult callTool(String tool, JsonNode input, Duration timeout, String threadId) {
            return tools.call(tool, input, timeout, threadId);
        }

        @Override
        public ToolResult webTool(String remoteName, JsonNode input, String threadId) {
            return web.call(remoteName, input, threadId);
        }

        @Override
        public ToolResult portUrl(int port, String threadId) {
            return ports.call(port, threadId);
        }

        @Override
        public List<McpToolBinding> mcpTools() {
            return mcpRegistry.bindings();
        }

        @Override
        public ToolResult mcpTool(String displayName, JsonNode input, String threadId) {
            return mcpRegistry.call(displayName, input, threadId);
        }

        @Override
        public String persistToolOutput(String content, String threadId) {
            return saveSandboxOutput(content, threadId);
        }

        @Override
        public void refreshMcp(SessionContext session, String threadId) {
            mcpRegistry.refresh(session, threadId);
        }

        @Override
        public DiagnosticsReporter diagnostics() {
            return diagnostics;
        }

        @Override
        public OtlpExporter otlp() {
            return otlp;
        }

        @Override
        public AttachmentStore attachments() {
            return attachments;
        }

        @Override
        public ModelReply chat(ModelRequest request) {
            return models.send(request);
        }

        @Override
        public List<Skill> skills() {
            if (cachedSkills == null) {
                cachedSkills = resources.skills();
            }
            return cachedSkills;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public List<Transcript.RestoredMessage> restoredMessages() {
            return restoredMessages;
        }

        @Override
        public WorkerMetadata metadata() {
            ControlPlane plane = controlPlane;
            return plane == null ? WorkerMetadata.empty() : plane.metadata();
        }

        @Override
        public void persistInternal(ObjectNode payload) {
            InternalEventStore store = internalEvents;
            if (store != null) {
                store.record(payload);
            }
        }

        @Override
        public ControlPlane.Decision requestApproval(
                String toolName, String toolUseId, JsonNode input, Duration timeout) {
            ControlPlane plane = controlPlane;
            if (plane == null) {
                return ControlPlane.Decision.allow(input);
            }
            return plane.await(plane.requestApproval(toolName, toolUseId, input, threadId(), timeout));
        }
    }

    private final class ControlSink implements ControlPlane.Sink {

        @Override
        public void emitControl(JsonNode payload) {
            transport.emit(epoch, List.of(WorkerTransport.OutboundEvent.of(payload)));
        }

        @Override
        public void saveMetadata(WorkerMetadata metadata) {
            transport.updateWorkerMetadata(epoch, metadata.toJson());
        }

        @Override
        public void applyModel(String model) {
            agent.applyModel(model);
        }

        @Override
        public void publishWorkerStatus(String status, JsonNode details) {
            try {
                transport.setWorkerStatus(epoch, status, null, details);
            } catch (OmaHttpException e) {
                LOG.warn("worker status update rejected: {}", e.getMessage());
            }
        }

        @Override
        public void emitSystemMessage(String text, String thread) {
            if (text == null || text.isBlank()) {
                return;
            }
            ObjectNode payload = Json.object();
            payload.put("type", "system");
            payload.put("uuid", "evt_" + java.util.UUID.randomUUID());
            if (thread != null && !thread.isBlank() && !"root".equals(thread)) {
                payload.put("session_thread_id", thread);
            }
            com.fasterxml.jackson.databind.node.ArrayNode content = payload.putArray("content");
            content.addObject().put("type", "text").put("text", text);
            try {
                transport.emit(epoch, List.of(new WorkerTransport.OutboundEvent(payload, false)));
            } catch (OmaHttpException e) {
                LOG.warn("system message rejected: {}", e.getMessage());
            }
        }
    }

    public static final class Builder {
        private OmaConfig config;
        private HarnessAgent agent;

        public Builder config(OmaConfig value) {
            this.config = value;
            return this;
        }

        public Builder agent(HarnessAgent value) {
            this.agent = value;
            return this;
        }

        public OmaHarness build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            if (agent == null) {
                throw new IllegalStateException("agent is required");
            }
            return new OmaHarness(config, agent);
        }
    }
}
