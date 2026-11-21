package ai.oma.harness.core.agent;

import ai.oma.harness.core.transport.InboundEvent;

/**
 * OMA worker 驱动的最小 agent 契约。
 *
 * <p>接入方不应直接实现这个接口，而是继承 {@link OmaAgentSupport}——后者已经用 final
 * 方法固化了全部 OMA 规范流程，接入方只需实现 {@code buildAgent}。此接口供 worker 层统一调度。
 */
public interface HarnessAgent extends AutoCloseable {

    /** 会话启动时由 worker 调用，注入与 OMA 交互的上下文。 */
    void start(AgentContext context) throws Exception;

    /** 投递一条 OMA 下行事件（用户消息、中断等）。 */
    void deliver(InboundEvent event) throws Exception;

    /** 运行时切换模型（由服务端 applyModel 指令触发）。 */
    default void applyModel(String model) {
    }

    /** 关闭并释放资源。 */
    @Override
    void close() throws Exception;
}
