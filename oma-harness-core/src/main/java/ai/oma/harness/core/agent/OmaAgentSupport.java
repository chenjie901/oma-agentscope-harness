package ai.oma.harness.core.agent;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.agentscope.OmaChatModel;
import ai.oma.harness.core.agentscope.OmaToolkit;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.SystemPromptAssembler;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.transport.InboundEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope 接入 OMA 的规范基类（SDK 核心扩展点）。
 *
 * <p><b>接入方只需要做一件事：实现 {@link #buildAgent(String)}，返回一个装配好的
 * {@link ReActAgent}。</b>其余所有与 OMA 协议相关的流程都由本类在 core 内部完成，不可绕过、
 * 也不应被覆写，包括：
 * <ul>
 *   <li>模型与工具桥接：自动创建 {@link OmaChatModel}（模型请求走 OMA 代理）与
 *       {@link OmaToolkit}（工具走 OMA 审批与分发，含 MCP 工具）；</li>
 *   <li>System prompt 组装：按 DSH 规范拼装 platform rules、环境提示、Agent instructions、
 *       cwd 与 memory catalog，并把结果通过 {@code systemPrompt} 参数传给 {@link #buildAgent(String)}；</li>
 *   <li>消息历史恢复：自动注入最近 {@link #RESTORE_MESSAGE_LIMIT} 条历史消息；</li>
 *   <li>事件流驱动：调用 AgentScope 2.0+ 的 {@code ReActAgent.streamEvents(...)}，把细粒度事件
 *       聚合为 OMA 的 {@code agent.message} / {@code agent.tool_use} / {@code agent.tool_result}，
 *       并负责去重与稳定 id；</li>
 *   <li>生命周期：turn 状态封装、interrupt 即时处理、运行时模型切换、关闭时释放 agent。</li>
 * </ul>
 *
 * <p>最小接入示例（用户自己的 Agent 类）：
 * <pre>{@code
 * public final class MyAgent extends OmaAgentSupport {
 *     public MyAgent(OmaConfig config, SessionContext session) {
 *         super(config, session);
 *     }
 *
 *     @Override
 *     protected ReActAgent buildAgent(String systemPrompt) {
 *         return ReActAgent.builder()
 *                 .name("my-agent")
 *                 .sysPrompt(systemPrompt)
 *                 .model(chatModel())
 *                 .toolkit(toolkit().toolkit())
 *                 .maxIters(20)
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * <p>宿主进程推荐直接用 {@code OmaHarnessRunner.builder().agentFactory(MyAgent::new).build().run()}
 * 启动；runner 会负责环境变量、claim、JUL→SLF4J 桥接与优雅关闭。参见
 * {@code oma-harness-agentscope} 模块的 {@code OmaAgent} 与 {@code Main} 参考实现。
 */
public abstract class OmaAgentSupport implements HarnessAgent {
    /** 恢复历史消息时最多注入的条数，避免上下文过长；只取最近 N 条。 */
    protected static final int RESTORE_MESSAGE_LIMIT = 40;

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final OmaConfig config;
    private final Set<String> emittedBlocks = new HashSet<>();
    private final Map<String, StringBuilder> textBlocks = new HashMap<>();
    private final Map<String, StringBuilder> toolCallInputs = new HashMap<>();
    private final Map<String, ToolResultAccumulator> toolResults = new HashMap<>();
    private volatile SessionContext session;
    private AgentContext context;
    private RuntimeContext runtimeContext;
    private OmaChatModel chatModel;
    private OmaToolkit toolkit;
    private ReActAgent agent;

    /**
     * 由 runner 在 claim 到会话后调用，接入方无需关心。
     *
     * @param config  worker 运行配置（endpoint、session、claim token 等）
     * @param session 服务端下发的会话上下文（model、prompt、权限、工具等）
     */
    protected OmaAgentSupport(OmaConfig config, SessionContext session) {
        this.config = config;
        this.session = session;
    }

    /**
     * 会话启动模板方法（final，不可覆写）。按顺序完成：创建 runtime context、模型代理、工具桥，
     * 同步 MCP 工具，然后调用 {@link #buildAgent(String)} 装配 agent，最后恢复历史消息。
     * 接入方无需调用此方法。
     */
    @Override
    public final void start(AgentContext context) {
        this.context = context;
        this.runtimeContext = RuntimeContext.builder()
                .sessionId(config.sessionId())
                .userId("oma")
                .build();
        this.chatModel = new OmaChatModel(config,
                context.metadata().model() == null ? session.model() : context.metadata().model(),
                session.apiBackend());
        this.chatModel.onRequest(this::refreshPermissions);
        this.toolkit = new OmaToolkit(context, () -> session);
        context.refreshMcp(session, context.threadId());
        this.toolkit.sync(session, context.mcpTools());
        this.agent = buildAgent(assemblePrompt(session));
        restoreMessages(context.restoredMessages());
    }

    /**
     * 组装 System prompt。默认按 DSH 规范组装；接入方通常无需覆写，如需自定义可覆写此方法。
     */
    protected String assemblePrompt(SessionContext session) {
        return SystemPromptAssembler.assembleRoot(session);
    }

    /**
     * <b>接入方唯一必须实现的方法。</b>
     *
     * <p>在这里用 AgentScope 的 builder 装配并返回你自己的 {@link ReActAgent}。组装好的 prompt
     * 已通过 {@code systemPrompt} 参数传入，应设置为 agent 的 system prompt。构建时请务必：
     * <ul>
     *   <li>用 {@link #chatModel()} 作为 model —— 这样所有模型调用都会走 OMA 代理；</li>
     *   <li>用 {@link #toolkit()} 的 {@code toolkit()} 作为 toolkit —— 这样工具调用会走
     *       OMA 的审批与分发，并自动包含 MCP 工具；</li>
     *   <li>不要自己创建 ChatModel 或 Toolkit，否则会绕过 OMA 规范。</li>
     * </ul>
     *
     * <p>你也可以在 builder 上装配任意其它 AgentScope 能力（middleware、stateStore 等），
     * SDK 只要求最终返回一个 {@link ReActAgent} 实例，其余由 core 持有并驱动。
     *
     * @param systemPrompt 由 core 按 DSH 规范组装好的 system prompt
     * @return 装配完成的 AgentScope ReActAgent，不能返回 null 之外的非法 agent
     */
    protected abstract ReActAgent buildAgent(String systemPrompt);

    /**
     * 恢复历史消息（core 内部实现，接入方无需实现）。只注入最近 N 条，避免上下文超长。
     */
    private void restoreMessages(List<Transcript.RestoredMessage> messages) {
        if (agent == null || messages == null || messages.isEmpty()) {
            return;
        }
        int start = Math.max(0, messages.size() - RESTORE_MESSAGE_LIMIT);
        AgentState state = agent.getAgentState(runtimeContext);
        for (Transcript.RestoredMessage message : messages.subList(start, messages.size())) {
            MsgRole role = "assistant".equals(message.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
            state.contextMutable().add(Msg.builder().role(role).textContent(message.text()).build());
        }
    }

    @Override
    public final void applyModel(String model) {
        if (chatModel != null) {
            chatModel.setModel(model == null || model.isBlank() ? session.model() : model);
        }
    }

    /**
     * 投递 OMA 下行事件（final，不可覆写）。处理 {@code user.message}（驱动一个回合）与
     * {@code user.interrupt}（即时中断当前回合）；其它事件类型忽略。
     * 回合内先发 TurnStarted/SessionRunning，结束后在 finally 中发 TurnCompleted/SessionIdle，
     * 保证任何情况下状态都能收敛。接入方无需关心。
     */
    @Override
    public final void deliver(InboundEvent event) {
        String type = event.eventType() == null ? "" : event.eventType();
        if ("user.interrupt".equals(type)) {
            interruptTurn();
            return;
        }
        if (!"user.message".equals(type)) {
            return;
        }
        String text = extractText(event.payload());
        if (text.isBlank()) {
            return;
        }
        log.info("turn start event={} text_length={} thread={}",
                event.eventId(), text.length(), context.threadId());
        if (refreshRequested(event.payload())) {
            refreshTools();
        }
        context.persistInternal(Transcript.user(event.eventId(), text));
        context.emit(new CanonicalEvent.TurnStarted(context.threadId()));
        context.emit(new CanonicalEvent.SessionRunning());
        try {
            Flux<AgentEvent> stream = streamTurn(text);
            if (stream != null) {
                stream.doOnNext(this::publishEvent).blockLast();
            }
        } finally {
            context.emit(new CanonicalEvent.TurnCompleted(context.threadId(), "end_turn"));
            context.emit(new CanonicalEvent.SessionIdle());
            log.info("turn end event={} thread={}", event.eventId(), context.threadId());
        }
    }

    /** 驱动一个回合：用 AgentScope 2.0+ 的 streamEvents 产生细粒度事件流（旧的 stream 已废弃）。 */
    private Flux<AgentEvent> streamTurn(String text) {
        if (agent == null) {
            return Flux.empty();
        }
        return agent.streamEvents(List.of(new UserMessage(text)), runtimeContext);
    }

    /** 即时中断当前回合：必须带 runtimeContext，否则落到 AgentScope 的默认槽位而不生效。 */
    private void interruptTurn() {
        if (agent != null) {
            agent.interrupt(runtimeContext);
        }
    }

    private void refreshPermissions() {
        session = SessionContext.refresh(config, context.threadId());
        log.debug("tool permissions refreshed tools={} permissions={}",
                session.runtimeTools().size(), session.toolPermissions());
    }

    private void refreshTools() {
        SessionContext refreshed = SessionContext.refresh(config, context.threadId());
        session = refreshed;
        context.refreshMcp(refreshed, context.threadId());
        toolkit.sync(refreshed, context.mcpTools());
        log.info("tools refreshed runtime={} permissions={} mcp={}",
                refreshed.runtimeTools(), refreshed.toolPermissions(), context.mcpTools().size());
    }

    private static boolean refreshRequested(JsonNode payload) {
        JsonNode flag = payload == null ? null : payload.get("oma_refresh_tools");
        return flag != null && flag.asBoolean(false);
    }

    /**
     * 把 AgentScope 的细粒度事件聚合映射为 OMA canonical 事件（core 内部实现）。
     * 文本按 replyId+blockId 聚合，工具入参与工具结果按 toolCallId 聚合，在各自的
     * End 事件时才真正发射，避免重复；同时做去重以保证 sevt_ 稳定 id。
     */
    protected final void publishEvent(AgentEvent event) {
        if (event instanceof TextBlockStartEvent start) {
            textBlocks.putIfAbsent(textKey(start.getReplyId(), start.getBlockId()), new StringBuilder());
        } else if (event instanceof TextBlockDeltaEvent delta) {
            textBlocks
                    .computeIfAbsent(textKey(delta.getReplyId(), delta.getBlockId()), k -> new StringBuilder())
                    .append(delta.getDelta());
        } else if (event instanceof TextBlockEndEvent end) {
            StringBuilder text = textBlocks.remove(textKey(end.getReplyId(), end.getBlockId()));
            if (text != null) {
                publishText(textKey(end.getReplyId(), end.getBlockId()), text.toString());
            }
        } else if (event instanceof ToolCallStartEvent start) {
            toolCallInputs.putIfAbsent(start.getToolCallId(), new StringBuilder());
        } else if (event instanceof ToolCallDeltaEvent delta) {
            toolCallInputs
                    .computeIfAbsent(delta.getToolCallId(), k -> new StringBuilder())
                    .append(delta.getDelta());
        } else if (event instanceof ToolCallEndEvent end) {
            StringBuilder args = toolCallInputs.remove(end.getToolCallId());
            publishToolUse(end.getToolCallId(), end.getToolCallName(), parseInput(args));
        } else if (event instanceof ToolResultStartEvent start) {
            toolResults.putIfAbsent(start.getToolCallId(), new ToolResultAccumulator());
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            toolResults
                    .computeIfAbsent(delta.getToolCallId(), k -> new ToolResultAccumulator())
                    .append(delta.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            ToolResultAccumulator result = toolResults.remove(end.getToolCallId());
            if (result != null) {
                publishToolResult(end.getToolCallId(), end.getToolCallName(), result.content(), end.getState());
            }
        }
    }

    private static String textKey(String replyId, String blockId) {
        return (replyId == null ? "" : replyId) + ":" + (blockId == null ? "" : blockId);
    }

    private static JsonNode parseInput(StringBuilder args) {
        if (args == null || args.length() == 0) {
            return Json.object();
        }
        try {
            return Json.mapper().readTree(args.toString());
        } catch (Exception e) {
            return Json.object();
        }
    }

    private void publishText(String messageId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String key = "text:" + messageId + ":" + text.hashCode();
        if (!emittedBlocks.add(key)) {
            return;
        }
        context.persistInternal(Transcript.assistant(Transcript.newUUID(), text));
        context.emit(new CanonicalEvent.MessageCompleted(
                context.threadId(), messageId, text, chatModel.lastRequestId()));
    }

    private void publishToolUse(String toolUseId, String name, JsonNode input) {
        String key = "tool:" + toolUseId;
        if (!emittedBlocks.add(key)) {
            return;
        }
        context.emit(new CanonicalEvent.ToolCallStarted(
                context.threadId(), toolUseId, name, input));
        log.info("tool call id={} name={} input={}", toolUseId, name, input);
    }

    private void publishToolResult(String toolUseId, String name, List<Map<String, Object>> content, ToolResultState state) {
        String key = "result:" + toolUseId;
        if (!emittedBlocks.add(key)) {
            return;
        }
        boolean failed = state == ToolResultState.ERROR
                || state == ToolResultState.DENIED
                || state == ToolResultState.INTERRUPTED;
        context.emit(new CanonicalEvent.ToolCallCompleted(
                context.threadId(),
                toolUseId,
                name,
                Json.mapper().valueToTree(Map.of()),
                content,
                failed));
        log.info("tool result id={} name={} error={} text_length={}",
                toolUseId, name, failed, textLength(content));
    }

    private static final class ToolResultAccumulator {
        private final StringBuilder text = new StringBuilder();

        void append(String delta) {
            if (delta != null) {
                text.append(delta);
            }
        }

        List<Map<String, Object>> content() {
            if (text.length() == 0) {
                return List.of();
            }
            return List.of(Map.of("type", "text", "text", text.toString()));
        }
    }

    private static int textLength(List<Map<String, Object>> content) {
        int length = 0;
        for (Map<String, Object> item : content) {
            Object text = item.get("text");
            if (text != null) {
                length += text.toString().length();
            }
        }
        return length;
    }

    private String extractText(JsonNode payload) {
        JsonNode message = payload == null ? null : payload.get("message");
        if (message == null) {
            return "";
        }
        JsonNode content = message.get("content");
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if (block.isTextual()) {
                builder.append(block.asText());
            } else if ("text".equals(Json.text(block, "type"))) {
                builder.append(Json.text(block, "text"));
            }
        }
        return builder.toString();
    }

    /** 当前 worker 运行配置，供接入方在 buildAgent 中读取。 */
    protected final OmaConfig config() {
        return config;
    }

    /** 当前会话上下文（model、prompt、权限、工具等），每次模型请求后会由 core 刷新。 */
    protected final SessionContext session() {
        return session;
    }

    /**
     * core 创建的模型代理。在 buildAgent 中把它传给 agent 的 model，即可让所有模型调用
     * 走 OMA 代理（含 chat_completions 后端自动切换与请求 id 追踪）。
     */
    protected final OmaChatModel chatModel() {
        return chatModel;
    }

    /**
     * core 创建的工具桥。在 buildAgent 中把 {@code toolkit()} 传给 agent 的 toolkit，
     * 即可让工具调用走 OMA 审批与分发，并自动包含服务端下发的 MCP 工具。
     */
    protected final OmaToolkit toolkit() {
        return toolkit;
    }

    /** 关闭 agent 释放资源（final，不可覆写）。接入方无需实现 closeAgent。 */
    @Override
    public final void close() throws Exception {
        if (agent != null) {
            agent.close();
        }
    }
}
