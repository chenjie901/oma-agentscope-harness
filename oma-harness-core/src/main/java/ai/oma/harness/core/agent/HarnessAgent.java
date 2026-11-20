package ai.oma.harness.core.agent;

import ai.oma.harness.core.transport.InboundEvent;

public interface HarnessAgent extends AutoCloseable {

    void start(AgentContext context) throws Exception;

    void deliver(InboundEvent event) throws Exception;

    default void applyModel(String model) {
    }

    @Override
    void close() throws Exception;
}
