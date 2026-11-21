package ai.oma.harness.core.agent;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.agentscope.OmaChatModel;
import ai.oma.harness.core.agentscope.OmaToolkit;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.compaction.CompactionConfig;
import ai.oma.harness.core.compaction.CompactionResult;
import ai.oma.harness.core.compaction.ConversationCompactor;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.SystemPromptAssembler;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.resources.Skill;
import ai.oma.harness.core.transport.InboundEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 关闭压缩的系统属性/环境变量名；默认开启。 */
    public static final String COMPACTION_ENABLED_KEY = "OMA_HARNESS_COMPACTION_ENABLED";

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final OmaConfig config;
    private final Set<String> emittedBlocks = new HashSet<>();
    private final Map<String, StringBuilder> textBlocks = new HashMap<>();
    private final Map<String, StringBuilder> thinkingBlocks = new HashMap<>();
    private final Map<String, StringBuilder> toolCallInputs = new HashMap<>();
    private final Map<String, ToolResultAccumulator> toolResults = new HashMap<>();
    private StreamEventPublisher streamPublisher;
    private volatile SessionContext session;
    private AgentContext context;
    private RuntimeContext runtimeContext;
    private OmaChatModel chatModel;
    private OmaToolkit toolkit;
    private ReActAgent agent;
    private String configuredSignature;
    private volatile Map<String, Skill> skillIndex = Map.of();
    private volatile boolean configRefreshedThisTurn;

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
        this.streamPublisher = new StreamEventPublisher(context, config.sessionId());
        this.toolkit.registerFrameworkTool(new OmaSkillTool(() -> skillIndex));
        context.refreshMcp(session, context.threadId());
        this.toolkit.sync(session, context.mcpTools());
        this.skillIndex = indexSkills(context.skills());
        this.agent = buildAgent(assemblePrompt(session));
        this.configuredSignature = session.signature();
        restoreMessages(context.restoredMessages());
    }

    private static Map<String, Skill> indexSkills(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return Map.of();
        }
        Map<String, Skill> index = new HashMap<>();
        for (Skill skill : skills) {
            if (skill != null && skill.name() != null && !skill.name().isBlank()) {
                index.put(skill.name(), skill);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 组装 System prompt。默认按 DSH 规范组装；接入方通常无需覆写，如需自定义可覆写此方法。
     */
    protected String assemblePrompt(SessionContext session) {
        List<Skill> skills = context == null ? List.of() : context.skills();
        return SystemPromptAssembler.assembleRoot(session, skills == null ? List.of() : skills);
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
     * 恢复历史消息（core 内部实现，接入方无需实现）。只注入最近 N 条，避免上下文超长；
     * 其中的压缩摘要记录恢复为带标记的 USER 消息，供后续压缩器识别与累计。
     */
    private void restoreMessages(List<Transcript.RestoredMessage> messages) {
        if (agent == null || messages == null || messages.isEmpty()) {
            return;
        }
        int start = Math.max(0, messages.size() - RESTORE_MESSAGE_LIMIT);
        AgentState state = agent.getAgentState(runtimeContext);
        for (Transcript.RestoredMessage message : messages.subList(start, messages.size())) {
            if (message.summary()) {
                state.contextMutable().add(Msg.builder()
                        .name(ConversationCompactor.SUMMARY_MSG_NAME)
                        .role(MsgRole.USER)
                        .textContent(message.text())
                        .build());
                continue;
            }
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
        String text = UserContentResolver.extractText(event.payload());
        List<ContentBlock> content = UserContentResolver.resolve(event.payload(), context.attachments());
        if (content.isEmpty()) {
            return;
        }
        log.info("turn start event={} text_length={} blocks={} thread={}",
                event.eventId(), text.length(), content.size(), context.threadId());
        boolean forceRefresh = refreshRequested(event.payload());
        configRefreshedThisTurn = false;
        applyConfigIfChanged(forceRefresh);
        context.persistInternal(Transcript.user(event.eventId(), text));
        context.emit(new CanonicalEvent.TurnStarted(context.threadId()));
        context.emit(new CanonicalEvent.SessionRunning());
        try {
            compactIfNeeded();
            Flux<AgentEvent> stream = streamTurn(content);
            if (stream != null) {
                stream.doOnNext(this::publishEvent).blockLast();
            }
        } finally {
            if (streamPublisher != null) {
                streamPublisher.stopAll();
            }
            context.emit(new CanonicalEvent.TurnCompleted(context.threadId(), "end_turn"));
            context.emit(new CanonicalEvent.SessionIdle());
            log.info("turn end event={} thread={}", event.eventId(), context.threadId());
        }
    }

    /** 驱动一个回合：用 AgentScope 2.0+ 的 streamEvents 产生细粒度事件流（旧的 stream 已废弃）。 */
    private Flux<AgentEvent> streamTurn(List<ContentBlock> content) {
        if (agent == null) {
            return Flux.empty();
        }
        return agent.streamEvents(List.of(new UserMessage(content)), runtimeContext);
    }

    /**
     * 上下文压缩编排（core 内部实现）。压缩失败或中断时降级为不压缩，不阻断当前回合；
     * 成功时把摘要写回 agent state、持久化摘要记录并上报 ContextCompacted 事件。
     */
    private void compactIfNeeded() {
        if (!compactionEnabled() || agent == null || context == null) {
            return;
        }
        AgentState state = agent.getAgentState(runtimeContext);
        List<Msg> allMessages = state.getContext();
        List<Msg> systemMessages = systemMessages(allMessages);
        List<Msg> conversation = withoutSystemMessages(allMessages);
        if (conversation.isEmpty()) {
            return;
        }
        ConversationCompactor compactor = new ConversationCompactor(this::summarize);
        try {
            Optional<CompactionResult> result = compactor
                    .compactIfNeeded(conversation, compactionConfig(), chatModel.getContextWindowSize())
                    .block();
            if (result == null || result.isEmpty()) {
                return;
            }
            CompactionResult compacted = result.get();
            List<Msg> mutable = state.contextMutable();
            mutable.clear();
            mutable.addAll(systemMessages);
            mutable.addAll(compacted.messages());
            context.persistInternal(Transcript.compacted(
                    Transcript.newUUID(), compacted.summary(), compacted.droppedMessages()));
            context.emit(new CanonicalEvent.ContextCompacted(
                    context.threadId(), compacted.summary(), compacted.droppedMessages()));
            log.info("context compacted thread={} dropped={} remaining={}",
                    context.threadId(), compacted.droppedMessages(), compacted.messages().size());
        } catch (RuntimeException e) {
            log.warn("context compaction failed, continuing without compaction: {}", e.getMessage());
        }
    }

    private static List<Msg> systemMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Msg> result = new ArrayList<>();
        for (Msg message : messages) {
            if (message.getRole() == MsgRole.SYSTEM) {
                result.add(message);
            }
        }
        return result;
    }

    private static List<Msg> withoutSystemMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Msg> result = new ArrayList<>();
        for (Msg message : messages) {
            if (message.getRole() != MsgRole.SYSTEM) {
                result.add(message);
            }
        }
        return result;
    }

    /** 经 OMA 代理发起一次独立的 compaction 摘要请求，不触发权限刷新，也不污染主模型请求 id。 */
    private Mono<String> summarize(List<Msg> prefix, String prompt) {
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "text", "text", prompt))));
        ModelRequest request = new ModelRequest(
                chatModel.getModelName(), null, messages, List.of(), 0, null, "compaction");
        return Mono.fromCallable(() -> context.chat(request).text());
    }

    /** 压缩是否开启：默认开启，设置 {@link #COMPACTION_ENABLED_KEY} 为 false 关闭。 */
    private static boolean compactionEnabled() {
        String value = System.getProperty(COMPACTION_ENABLED_KEY);
        if (value == null || value.isBlank()) {
            value = System.getenv(COMPACTION_ENABLED_KEY);
        }
        return value == null || !"false".equalsIgnoreCase(value.strip());
    }

    /** 压缩配置钩子：接入方可覆写以调整触发阈值、保留策略与摘要 prompt；默认使用 SDK 默认值。 */
    protected CompactionConfig compactionConfig() {
        String override = System.getProperty("OMA_HARNESS_COMPACTION_TRIGGER_MESSAGES");
        if (override == null || override.isBlank()) {
            override = System.getenv("OMA_HARNESS_COMPACTION_TRIGGER_MESSAGES");
        }
        CompactionConfig.Builder builder = CompactionConfig.builder();
        if (override != null && !override.isBlank()) {
            try {
                int trigger = Integer.parseInt(override.strip());
                if (trigger > 0) {
                    builder.triggerMessages(trigger);
                }
            } catch (NumberFormatException e) {
                log.warn("invalid compaction trigger override: {}", override);
            }
        }
        return builder.build();
    }

    /** 即时中断当前回合：必须带 runtimeContext，否则落到 AgentScope 的默认槽位而不生效。 */
    private void interruptTurn() {
        if (agent != null) {
            agent.interrupt(runtimeContext);
        }
    }

    /**
     * 在用户消息边界按 DSH refresh 语义对齐配置：拉取一次 session_context 并计算签名。
     * 服务端对每条 user.message 注入 oma_refresh_tools=true（DSH 兼容），因此 force 很常见；
     * 仅在配置签名真正变化时才重建 agent，force 但签名未变时只同步工具/MCP 与 skill 索引，
     * 避免每轮重建 agent 的开销与历史迁移风险。
     */
    private void applyConfigIfChanged(boolean force) {
        if (context == null) {
            return;
        }
        SessionContext refreshed = refreshSession();
        configRefreshedThisTurn = true;
        String nextSignature = refreshed.signature();
        boolean changed = configuredSignature == null || !configuredSignature.equals(nextSignature);
        if (changed) {
            log.info("session config changed, rebuilding agent thread={} force={} old={} new={}",
                    context.threadId(), force, configuredSignature, nextSignature);
            rebuildAgent(refreshed, nextSignature);
            return;
        }
        session = refreshed;
        if (force) {
            syncToolsOnly(refreshed);
        }
    }

    /** 签名未变但被强制刷新时，只同步 MCP/工具/skill 数据，不重建 agent、不迁移历史。 */
    private void syncToolsOnly(SessionContext refreshed) {
        context.refreshMcp(refreshed, context.threadId());
        toolkit.sync(refreshed, context.mcpTools());
        skillIndex = indexSkills(context.skills());
        log.debug("tools refreshed without agent rebuild thread={} tools={} mcp={}",
                context.threadId(), refreshed.runtimeTools().size(), context.mcpTools().size());
    }

    /** 重建 agent 并迁移历史消息；去重状态、流式发布器等跨 turn 状态保持不变。 */
    private void rebuildAgent(SessionContext refreshed, String signature) {
        List<Msg> history = exportHistory();
        ReActAgent previous = agent;
        session = refreshed;
        configuredSignature = signature;
        if (chatModel != null) {
            chatModel.setModel(refreshed.model());
        }
        context.refreshMcp(refreshed, context.threadId());
        toolkit.sync(refreshed, context.mcpTools());
        List<Skill> skills = context.skills();
        skillIndex = indexSkills(skills);
        ReActAgent replacement = buildAgent(assemblePrompt(refreshed));
        if (replacement == null) {
            log.warn("buildAgent returned null on rebuild; keeping previous agent");
            return;
        }
        if (history != null && !history.isEmpty()) {
            replacement.getAgentState(runtimeContext).contextMutable().addAll(history);
        }
        agent = replacement;
        if (previous != null && previous != replacement) {
            try {
                previous.close();
            } catch (Exception e) {
                log.warn("failed to close previous agent: {}", e.getMessage());
            }
        }
        log.info("agent rebuilt thread={} history={} tools={} mcp={}",
                context.threadId(), history == null ? 0 : history.size(),
                refreshed.runtimeTools().size(), context.mcpTools().size());
    }

    private List<Msg> exportHistory() {
        if (agent == null) {
            return List.of();
        }
        try {
            return List.copyOf(agent.getAgentState(runtimeContext).getContext());
        } catch (RuntimeException e) {
            log.warn("failed to export agent history: {}", e.getMessage());
            return List.of();
        }
    }

    /** 模型请求前的轻量刷新：本轮已在 deliver 入口 GET 过则跳过，避免同轮重复请求。 */
    private void refreshPermissions() {
        if (configRefreshedThisTurn) {
            return;
        }
        session = refreshSession();
        configRefreshedThisTurn = true;
        log.debug("tool permissions refreshed tools={} permissions={}",
                session.runtimeTools().size(), session.toolPermissions());
    }

    /** 拉取当前 session_context；接入方可覆写以注入固定上下文或自适应刷新策略。 */
    protected SessionContext refreshSession() {
        return SessionContext.refresh(config, context.threadId());
    }

    private static boolean refreshRequested(JsonNode payload) {
        JsonNode flag = payload == null ? null : payload.get("oma_refresh_tools");
        return flag != null && flag.asBoolean(false);
    }

    /**
     * 把 AgentScope 的细粒度事件映射为 OMA 事件（core 内部实现）。
     * 文本/思考块在 Delta 阶段实时上报 stream_event 供前端逐 token 渲染，在 End 阶段
     * 发射最终 canonical 消息并用稳定 id 收敛预览帧；工具入参与工具结果按 toolCallId 聚合。
     */
    protected final void publishEvent(AgentEvent event) {
        if (event instanceof TextBlockStartEvent start) {
            textBlocks.putIfAbsent(textKey(start.getReplyId(), start.getBlockId()), new StringBuilder());
            if (streamPublisher != null) {
                streamPublisher.blockStart(start.getReplyId(), start.getBlockId(), false);
            }
        } else if (event instanceof TextBlockDeltaEvent delta) {
            textBlocks
                    .computeIfAbsent(textKey(delta.getReplyId(), delta.getBlockId()), k -> new StringBuilder())
                    .append(delta.getDelta());
            if (streamPublisher != null) {
                streamPublisher.blockDelta(delta.getReplyId(), delta.getBlockId(), delta.getDelta());
            }
        } else if (event instanceof TextBlockEndEvent end) {
            String key = textKey(end.getReplyId(), end.getBlockId());
            StringBuilder text = textBlocks.remove(key);
            if (streamPublisher != null) {
                streamPublisher.blockEnd(end.getReplyId(), end.getBlockId());
            }
            if (text != null) {
                String finalId = streamPublisher == null ? key
                        : streamPublisher.finalEventId(end.getReplyId(), end.getBlockId(), false);
                publishText(finalId, text.toString());
            }
        } else if (event instanceof ThinkingBlockStartEvent start) {
            thinkingBlocks.putIfAbsent(textKey(start.getReplyId(), start.getBlockId()), new StringBuilder());
            if (streamPublisher != null) {
                streamPublisher.blockStart(start.getReplyId(), start.getBlockId(), true);
            }
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            thinkingBlocks
                    .computeIfAbsent(textKey(delta.getReplyId(), delta.getBlockId()), k -> new StringBuilder())
                    .append(delta.getDelta());
        } else if (event instanceof ThinkingBlockEndEvent end) {
            String key = textKey(end.getReplyId(), end.getBlockId());
            StringBuilder text = thinkingBlocks.remove(key);
            if (streamPublisher != null) {
                streamPublisher.blockEnd(end.getReplyId(), end.getBlockId());
            }
            if (text != null) {
                String finalId = streamPublisher == null ? key
                        : streamPublisher.finalEventId(end.getReplyId(), end.getBlockId(), true);
                publishThinking(finalId, text.toString());
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

    private void publishThinking(String messageId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String key = "thinking:" + messageId + ":" + text.hashCode();
        if (!emittedBlocks.add(key)) {
            return;
        }
        context.emit(new CanonicalEvent.ThinkingDelta(
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
