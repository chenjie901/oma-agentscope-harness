package ai.oma.harness.agentscope;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.agent.AgentContext;
import ai.oma.harness.core.agent.HarnessAgent;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.transport.InboundEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OmaAgent implements HarnessAgent {
    private static final Logger LOG = LoggerFactory.getLogger(OmaAgent.class);
    private static final StreamOptions STREAM_OPTIONS = StreamOptions.builder()
            .incremental(false)
            .build();
    private static final int RESTORE_MESSAGE_LIMIT = 40;

    private final OmaConfig config;
    private final Set<String> emittedBlocks = new HashSet<>();
    private volatile SessionContext session;
    private ReActAgent agent;
    private OmaChatModel chatModel;
    private OmaToolkit toolkit;
    private AgentContext context;
    private RuntimeContext runtimeContext;

    public OmaAgent(OmaConfig config, SessionContext session) {
        this.config = config;
        this.session = session;
    }

    @Override
    public void start(AgentContext context) {
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
        this.toolkit.sync(session);
        this.agent = ReActAgent.builder()
                .name("oma-harness")
                .sysPrompt(prompt(session))
                .model(chatModel)
                .toolkit(toolkit.toolkit())
                .maxIters(20)
                .build();
        restore(context.restoredMessages());
    }

    private static String prompt(SessionContext session) {
        String systemPrompt = session.systemPrompt();
        return systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    private void restore(List<Transcript.RestoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
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
    public void applyModel(String model) {
        if (chatModel != null) {
            chatModel.setModel(model == null || model.isBlank() ? session.model() : model);
        }
    }

    @Override
    public void deliver(InboundEvent event) {
        String type = event.eventType() == null ? "" : event.eventType();
        if ("user.interrupt".equals(type)) {
            agent.interrupt(runtimeContext);
            return;
        }
        if (!"user.message".equals(type)) {
            return;
        }
        String text = extractText(event.payload());
        if (text.isBlank()) {
            return;
        }
        LOG.info("turn start event={} text_length={} thread={}",
                event.eventId(), text.length(), context.threadId());
        if (refreshRequested(event.payload())) {
            refreshTools();
        }
        context.persistInternal(Transcript.user(event.eventId(), text));
        context.emit(new CanonicalEvent.TurnStarted(context.threadId()));
        context.emit(new CanonicalEvent.SessionRunning());
        try {
            Flux<Event> stream = agent.stream(List.of(new UserMessage(text)), STREAM_OPTIONS, this.runtimeContext);
            stream.doOnNext(this::publish).blockLast();
        } finally {
            context.emit(new CanonicalEvent.TurnCompleted(context.threadId(), "end_turn"));
            context.emit(new CanonicalEvent.SessionIdle());
            LOG.info("turn end event={} thread={}", event.eventId(), context.threadId());
        }
    }

    private void refreshPermissions() {
        session = SessionContext.refresh(config, context.threadId());
        LOG.debug("tool permissions refreshed tools={} permissions={}",
                session.runtimeTools().size(), session.toolPermissions());
    }

    private void refreshTools() {
        SessionContext refreshed = SessionContext.refresh(config, context.threadId());
        session = refreshed;
        toolkit.sync(refreshed);
        LOG.info("tools refreshed runtime={} permissions={}",
                refreshed.runtimeTools(), refreshed.toolPermissions());
    }

    private static boolean refreshRequested(JsonNode payload) {
        JsonNode flag = payload == null ? null : payload.get("oma_refresh_tools");
        return flag != null && flag.asBoolean(false);
    }

    private void publish(Event event) {
        Msg message = event.getMessage();
        if (message == null) {
            return;
        }
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock) {
                publishText(message.getId(), textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                publishToolUse(toolUse);
            } else if (block instanceof ToolResultBlock toolResult) {
                publishToolResult(toolResult);
            }
        }
    }

    private void publishText(String messageId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String key = (messageId == null ? "" : messageId) + ":text:" + text.hashCode();
        if (!emittedBlocks.add(key)) {
            return;
        }
        context.persistInternal(Transcript.assistant(Transcript.newUUID(), text));
        context.emit(new CanonicalEvent.MessageCompleted(
                context.threadId(), messageId, text, chatModel.lastRequestId()));
    }

    private void publishToolUse(ToolUseBlock toolUse) {
        String key = "tool:" + toolUse.getId();
        if (!emittedBlocks.add(key)) {
            return;
        }
        context.emit(new CanonicalEvent.ToolCallStarted(
                context.threadId(),
                toolUse.getId(),
                toolUse.getName(),
                Json.mapper().valueToTree(toolUse.getInput() == null ? Map.of() : toolUse.getInput())));
        LOG.info("tool call id={} name={} input={}",
                toolUse.getId(), toolUse.getName(), toolUse.getInput());
    }

    private void publishToolResult(ToolResultBlock toolResult) {
        String key = "result:" + toolResult.getId();
        if (!emittedBlocks.add(key)) {
            return;
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock block : toolResult.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                content.add(Map.of("type", "text", "text", textBlock.getText()));
            }
        }
        boolean failed = toolResult.getState() == ToolResultState.ERROR
                || toolResult.getState() == ToolResultState.DENIED
                || toolResult.getState() == ToolResultState.INTERRUPTED;
        context.emit(new CanonicalEvent.ToolCallCompleted(
                context.threadId(),
                toolResult.getId(),
                toolResult.getName(),
                Json.mapper().valueToTree(Map.of()),
                content,
                failed));
        LOG.info("tool result id={} name={} error={} text_length={}",
                toolResult.getId(), toolResult.getName(), failed, textLength(content));
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

    @Override
    public void close() throws Exception {
        if (agent != null) {
            agent.close();
        }
    }

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a coding agent running inside an OMA managed sandbox.
            Use the provided tools for every filesystem and shell action.
            Prefer small, verifiable steps and report outcomes precisely.
            """;
}
