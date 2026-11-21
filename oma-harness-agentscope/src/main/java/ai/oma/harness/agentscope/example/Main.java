package ai.oma.harness.agentscope.example;

import ai.oma.harness.agentscope.OmaAgent;
import ai.oma.harness.core.OmaHarnessRunner;

/**
 * 可运行的接入示例：启动一个 AgentScope harness 并接入 OMA。
 *
 * <p>运行前需设置环境变量：
 * <ul>
 *   <li>{@code OMA_API_URL}：OMA 服务端地址，例如 http://127.0.0.1:48080；</li>
 *   <li>{@code OMA_BRAIN_CLAIM_TOKEN}：与服务端 brain claim token 一致。</li>
 * </ul>
 * 换成自己的 Agent 时，只需把 {@code OmaAgent::new} 换成 {@code MyAgent::new}。
 */
public final class Main {
    private Main() {
    }

    /** 程序入口：构建 runner 并阻塞运行。 */
    public static void main(String[] args) {
        OmaHarnessRunner.builder()
                .agentFactory(OmaAgent::new)
                .build()
                .run();
    }
}
