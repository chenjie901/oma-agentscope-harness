package ai.oma.harness.core;

import ai.oma.harness.core.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmaHarnessRunnerTest {

    @Test
    void requiresAgentFactory() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                OmaHarnessRunner.builder()
                        .endpoint("http://127.0.0.1:48080")
                        .claimToken("tok")
                        .installJulBridge(false)
                        .installShutdownHook(false)
                        .build());
        assertTrue(error.getMessage().contains("agentFactory"));
    }

    @Test
    void buildsWorkerWithExplicitParams() {
        OmaHarnessRunner runner = OmaHarnessRunner.builder()
                .endpoint("http://127.0.0.1:48080")
                .claimToken("tok")
                .concurrency(2)
                .workerId("worker-test")
                .agentFactory((config, session) -> noopAgent())
                .installJulBridge(false)
                .installShutdownHook(false)
                .build();

        assertNotNull(runner.worker());
        runner.close();
    }

    @Test
    void missingEndpointFailsClearly() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                OmaHarnessRunner.builder()
                        .claimToken("tok")
                        .agentFactory((config, session) -> noopAgent())
                        .installJulBridge(false)
                        .installShutdownHook(false)
                        .build());
        assertTrue(error.getMessage().contains("endpoint"));
    }

    private static HarnessAgent noopAgent() {
        return new HarnessAgent() {
            @Override
            public void start(ai.oma.harness.core.agent.AgentContext context) {
            }

            @Override
            public void deliver(ai.oma.harness.core.transport.InboundEvent event) {
            }

            @Override
            public void close() {
            }
        };
    }
}
