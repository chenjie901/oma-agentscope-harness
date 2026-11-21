package ai.oma.harness.core;

import ai.oma.harness.core.agent.HarnessAgent;
import ai.oma.harness.core.claim.BrainClaimClient;
import ai.oma.harness.core.claim.ClaimedSession;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.observability.SessionMdc;
import ai.oma.harness.core.transport.OmaHttpException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * worker 主循环：长轮询 claim 会话、为每个会话创建 agent 与 harness、投递事件、心跳维持租约，
 * 并在退出时释放 claim。接入方通常用 {@link OmaHarnessRunner} 间接使用它；这里只暴露给需要
 * 精细控制生命周期的高级场景（见 builder 的 endpoint / agentFactory / concurrency 等配置）。
 */
public final class OmaWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OmaWorker.class);
    private static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 5_000, 10_000, 30_000};

    public interface AgentFactory {
        HarnessAgent create(OmaConfig config, SessionContext context);
    }

    private final URI endpoint;
    private final long blockMs;
    private final int concurrency;
    private final Duration idleTimeout;
    private final AgentFactory agentFactory;
    private final BrainClaimClient claims;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    private OmaWorker(URI endpoint, String claimToken, String workerId, long blockMs, int concurrency,
                      Duration idleTimeout, AgentFactory agentFactory) {
        this.endpoint = endpoint;
        this.blockMs = blockMs;
        this.concurrency = concurrency;
        this.idleTimeout = idleTimeout;
        this.agentFactory = agentFactory;
        this.claims = new BrainClaimClient(endpoint, claimToken, workerId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void run() {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Thread thread = new Thread(this::claimLoop, "oma-worker-claim-" + i);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void claimLoop() {
        int failures = 0;
        while (!closed.get()) {
            ClaimedSession claim;
            try {
                claim = claims.claim(blockMs);
                failures = 0;
            } catch (OmaHttpException e) {
                if (e.status() == 401 || e.status() == 403 || e.status() == 404) {
                    throw new IllegalStateException(
                            "brain claim rejected with HTTP " + e.status()
                                    + "; check that the claim token matches the backend", e);
                }
                long delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length - 1)];
                failures++;
                LOG.warn("brain claim failed, retrying in " + delay + "ms: {}", e.getMessage());
                sleep(delay);
                continue;
            }
            if (claim == null) {
                continue;
            }
            serve(claim);
        }
    }

    private void serve(ClaimedSession claim) {
        Map<String, String> previousMdc = SessionMdc.captureAndPut(claim.sessionId());
        try {
            serveClaim(claim);
        } finally {
            SessionMdc.restore(previousMdc);
        }
    }

    private void serveClaim(ClaimedSession claim) {
        if (!active.add(claim.sessionId())) {
            LOG.warn("session " + claim.sessionId() + " is already served by this worker; ignoring duplicate claim epoch="
                    + claim.workerEpoch());
            return;
        }
        OmaConfig config = OmaConfig.builder()
                .endpoint(endpoint)
                .sessionId(claim.sessionId())
                .ingressToken(claim.ingressToken())
                .modelToken(claim.modelToken())
                .workerEpoch(claim.workerEpoch())
                .build();
        LOG.info("claimed session " + claim.sessionId() + " epoch=" + claim.workerEpoch());
        try {
            SessionContext context = SessionContext.load(config);
            LOG.info("session context model=" + context.model() + " backend=" + context.apiBackend()
                    + " runtimeTools=" + context.runtimeTools() + " permissions=" + context.toolPermissions());
            HarnessAgent agent = agentFactory.create(config, context);
            OmaHarness harness = OmaHarness.builder().config(config).agent(agent).build();
            ScheduledExecutorService idleWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "oma-worker-idle");
                thread.setDaemon(true);
                return thread;
            });
            idleWatchdog.scheduleWithFixedDelay(
                    SessionMdc.wrap(claim.sessionId(), () -> retireIfIdle(harness, claim)), 2, 2, TimeUnit.SECONDS);
            try {
                harness.run();
            } finally {
                idleWatchdog.shutdownNow();
                harness.close();
            }
            LOG.info("session " + claim.sessionId() + " finished");
        } catch (OmaHttpException e) {
            LOG.warn("session " + claim.sessionId() + " failed: {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("session " + claim.sessionId() + " failed", e);
        } finally {
            active.remove(claim.sessionId());
            try {
                claims.release(claim, "idle");
                LOG.debug("claim released session={} epoch={} reason=idle", claim.sessionId(), claim.workerEpoch());
            } catch (RuntimeException e) {
                LOG.debug("release failed: {}", e.getMessage());
            }
        }
    }

    private void retireIfIdle(OmaHarness harness, ClaimedSession claim) {
        if (harness.idleFor(idleTimeout)) {
            LOG.info("session " + claim.sessionId() + " idle, releasing claim");
            harness.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public static final class Builder {
        private URI endpoint;
        private String claimToken;
        private String workerId = UUID.randomUUID().toString();
        private long blockMs = 25_000;
        private int concurrency = 4;
        private Duration idleTimeout = Duration.ofSeconds(60);
        private AgentFactory agentFactory;

        public Builder endpoint(URI value) {
            this.endpoint = value;
            return this;
        }

        public Builder endpoint(String value) {
            this.endpoint = URI.create(value);
            return this;
        }

        public Builder claimToken(String value) {
            this.claimToken = value;
            return this;
        }

        public Builder workerId(String value) {
            this.workerId = value;
            return this;
        }

        public Builder blockMs(long value) {
            this.blockMs = value;
            return this;
        }

        public Builder concurrency(int value) {
            this.concurrency = value;
            return this;
        }

        public Builder idleTimeout(Duration value) {
            this.idleTimeout = value;
            return this;
        }

        public Builder agentFactory(AgentFactory value) {
            this.agentFactory = value;
            return this;
        }

        public OmaWorker build() {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint is required");
            }
            if (claimToken == null || claimToken.isBlank()) {
                throw new IllegalArgumentException("claimToken is required");
            }
            if (agentFactory == null) {
                throw new IllegalArgumentException("agentFactory is required");
            }
            return new OmaWorker(endpoint, claimToken, workerId, blockMs, Math.max(1, concurrency),
                    idleTimeout == null ? Duration.ofSeconds(60) : idleTimeout, agentFactory);
        }
    }
}
