package ai.oma.harness.agentscope;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.agent.OmaAgentSupport;
import ai.oma.harness.core.claim.SessionContext;
import io.agentscope.core.ReActAgent;

/**
 * AgentScope 接入 OMA 的参考实现。
 *
 * <p>展示接入方需要写的全部代码：继承 {@link OmaAgentSupport}，只实现
 * {@link #buildAgent(String)}。注意 model 用 {@code chatModel()}、toolkit 用
 * {@code toolkit().toolkit()}，这样模型调用与工具调用都会走 OMA 代理与审批。
 * 接入方可以照此写自己的 Agent，并在 builder 上装配其它 AgentScope 组件。
 */
public final class OmaAgent extends OmaAgentSupport {
    /** 构造器签名由 runner 的 agentFactory 约定，接入方照抄即可。 */
    public OmaAgent(OmaConfig config, SessionContext session) {
        super(config, session);
    }

    /**
     * 唯一扩展点：装配并返回 ReActAgent。systemPrompt 已由 SDK 按 DSH 规范组装好，
     * 这里只需把它设置到 agent，并接入 OMA 的模型与工具桥。
     */
    @Override
    protected ReActAgent buildAgent(String systemPrompt) {
        return ReActAgent.builder()
                .name("oma-harness")
                .sysPrompt(systemPrompt)
                .model(chatModel())
                .toolkit(toolkit().toolkit())
                .maxIters(20)
                .build();
    }
}
