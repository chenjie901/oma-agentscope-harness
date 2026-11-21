package ai.oma.harness.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 一站式启动入口：接入方只需提供一个 agent 工厂（通常是 {@code MyAgent::new}），
 * 其余全部由 SDK 完成——读取环境变量、安装 JUL→SLF4J 桥接、构建 {@link OmaWorker}、
 * 注册 shutdown hook。
 *
 * <p>最简用法（环境变量 OMA_API_URL、OMA_BRAIN_CLAIM_TOKEN 需已配置）：
 * <pre>{@code
 * OmaHarnessRunner.builder()
 *         .agentFactory(MyAgent::new)
 *         .build()
 *         .run();
 * }</pre>
 *
 * <p>环境变量与 builder 显式参数的关系：显式设置的优先；未设置时从环境变量读取：
 * {@code OMA_API_URL}（endpoint）、{@code OMA_BRAIN_CLAIM_TOKEN}（claim token）、
 * {@code OMA_BRAIN_CLAIM_CONCURRENCY}（并发数，默认 1）。
 */
public final class OmaHarnessRunner {
    private static final Logger LOG = LoggerFactory.getLogger(OmaHarnessRunner.class);

    private final OmaWorker worker;

    private OmaHarnessRunner(OmaWorker worker) {
        this.worker = worker;
    }

    /** 启动 worker 并阻塞运行，直到进程退出。 */
    public void run() {
        worker.run();
    }

    /** 暴露底层 worker，供高级场景直接使用；普通接入方无需关心。 */
    public OmaWorker worker() {
        return worker;
    }

    /** 关闭 worker 并释放 claim；注册了 shutdown hook 时进程退出会自动调用。 */
    public void close() {
        worker.close();
    }

    /** 创建 builder，接入入口。 */
    public static Builder builder() {
        return new Builder();
    }

    /** runner 的构建器。必填项只有 {@link #agentFactory}，其余都有环境变量或默认值兜底。 */
    public static final class Builder {
        private URI endpoint;
        private String claimToken;
        private String workerId;
        private Long blockMs;
        private Integer concurrency;
        private Duration idleTimeout;
        private OmaWorker.AgentFactory agentFactory;
        private boolean installJulBridge = true;
        private boolean installShutdownHook = true;

        public Builder endpoint(URI value) {
            this.endpoint = value;
            return this;
        }

        public Builder endpoint(String value) {
            this.endpoint = value == null || value.isBlank() ? null : URI.create(value);
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

        /**
         * 必填：提供用户自己的 Agent 工厂。通常写成 {@code MyAgent::new}，
         * 其中 MyAgent 继承 {@code OmaAgentSupport} 并实现 {@code buildAgent}。
         */
        public Builder agentFactory(OmaWorker.AgentFactory value) {
            this.agentFactory = value;
            return this;
        }

        public Builder installJulBridge(boolean value) {
            this.installJulBridge = value;
            return this;
        }

        public Builder installShutdownHook(boolean value) {
            this.installShutdownHook = value;
            return this;
        }

        public OmaHarnessRunner build() {
            if (agentFactory == null) {
                throw new IllegalArgumentException("agentFactory is required");
            }
            if (installJulBridge) {
                SLF4JBridgeHandler.removeHandlersForRootLogger();
                SLF4JBridgeHandler.install();
            }
            Map<String, String> env = System.getenv();
            String resolvedEndpoint = endpoint == null ? env.get("OMA_API_URL") : endpoint.toString();
            String resolvedClaimToken = claimToken == null ? env.get("OMA_BRAIN_CLAIM_TOKEN") : claimToken;
            int resolvedConcurrency = concurrency == null
                    ? optionalInt(env.get("OMA_BRAIN_CLAIM_CONCURRENCY"), 1) : Math.max(1, concurrency);
            if (resolvedEndpoint == null || resolvedEndpoint.isBlank()) {
                throw new IllegalStateException("missing endpoint: set OMA_API_URL or builder.endpoint(...)");
            }
            if (resolvedClaimToken == null || resolvedClaimToken.isBlank()) {
                throw new IllegalStateException("missing claimToken: set OMA_BRAIN_CLAIM_TOKEN or builder.claimToken(...)");
            }
            OmaWorker.Builder workerBuilder = OmaWorker.builder()
                    .endpoint(resolvedEndpoint)
                    .claimToken(resolvedClaimToken)
                    .concurrency(resolvedConcurrency)
                    .agentFactory(agentFactory);
            if (workerId != null && !workerId.isBlank()) {
                workerBuilder.workerId(workerId);
            }
            if (blockMs != null) {
                workerBuilder.blockMs(blockMs);
            }
            if (idleTimeout != null) {
                workerBuilder.idleTimeout(idleTimeout);
            }
            OmaWorker worker = workerBuilder.build();
            LOG.info("harness runner ready endpoint={} concurrency={}", resolvedEndpoint, resolvedConcurrency);
            if (installShutdownHook) {
                Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
            }
            return new OmaHarnessRunner(worker);
        }

        private static int optionalInt(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
