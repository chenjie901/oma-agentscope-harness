package ai.oma.harness.agentscope.example;

import ai.oma.harness.agentscope.OmaAgent;
import ai.oma.harness.core.OmaWorker;

import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private Main() {
    }

    public static void main(String[] args) {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        Map<String, String> env = System.getenv();
        String endpoint = required(env, "OMA_API_URL");
        String claimToken = required(env, "OMA_BRAIN_CLAIM_TOKEN");
        int concurrency = optionalInt(env, "OMA_BRAIN_CLAIM_CONCURRENCY", 1);
        LOG.info("starting harness endpoint=" + endpoint + " concurrency=" + concurrency);

        OmaWorker worker = OmaWorker.builder()
                .endpoint(endpoint)
                .claimToken(claimToken)
                .concurrency(concurrency)
                .agentFactory(OmaAgent::new)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
        worker.run();
    }

    private static int optionalInt(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return value;
    }
}
