package ai.oma.harness.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * harness 内部的规范化事件（canonical event），与具体的 OMA wire 协议解耦。
 *
 * <p>core 把 agent 产生的动作统一表达为这些 record，再由 {@link OmaEventMapper} 转换为
 * OMA 服务端要求的 JSON 事件。接入方通常不直接构造它们，只在自定义 AgentContext 实现里
 * 通过 {@code context.emit(...)} 发射。
 */
public sealed interface CanonicalEvent {

    /** 一个回合开始。turn 状态最终通过 PUT /worker 上报，不作为普通事件发送。 */
    record TurnStarted(String threadId) implements CanonicalEvent {
    }

    /** 一个回合结束。 */
    record TurnCompleted(String threadId, String stopReason) implements CanonicalEvent {
    }

    /** 文本增量（流式）。 */
    record MessageDelta(String threadId, String messageId, String text) implements CanonicalEvent {
    }

    /** 一条完整文本消息；messageId 是稳定 id 的来源，requestId 关联模型请求。 */
    record MessageCompleted(String threadId, String messageId, String text, String requestId) implements CanonicalEvent {
    }

    /** 思考内容增量。 */
    record ThinkingDelta(String threadId, String messageId, String text, String requestId) implements CanonicalEvent {
    }

    /** 工具调用开始，携带工具入参。 */
    record ToolCallStarted(String threadId, String toolCallId, String name, JsonNode input) implements CanonicalEvent {
    }

    /** 工具调用完成，携带结果内容块与是否失败标记。 */
    record ToolCallCompleted(String threadId, String toolCallId, String name, JsonNode input,
                             List<Map<String, Object>> content, boolean error) implements CanonicalEvent {
    }

    /** 上下文压缩完成。 */
    record ContextCompacted(String threadId, String summary, int droppedMessages) implements CanonicalEvent {
    }

    /** 会话进入 idle 状态。 */
    record SessionIdle() implements CanonicalEvent {
    }

    /** 会话进入 running 状态。 */
    record SessionRunning() implements CanonicalEvent {
    }

    /** 会话错误。 */
    record SessionError(String message) implements CanonicalEvent {
    }

    /** 直接透传的原生 OMA 事件（json），用于 canonical 尚未覆盖的特殊场景。 */
    record Native(JsonNode payload) implements CanonicalEvent {
    }
}
