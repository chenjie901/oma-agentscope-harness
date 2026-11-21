package ai.oma.harness.core.tools;

import ai.oma.harness.core.agent.AgentContext;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.tools.ToolCatalog.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行器：OmaToolkit 注册的工具最终都走这里——先发工具调用请求、必要时等待用户审批，
 * 再返回 {@link ToolResult}。它是"工具调用走 OMA 规范"的关键，接入方不要绕过。
 */
public final class ToolRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRunner.class);
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SANDBOX_TIMEOUT = Duration.ofMinutes(3);
    private static final int TOOL_OUTPUT_THRESHOLD = 100_000;
    private static final int TOOL_OUTPUT_PREVIEW = 1_000;

    private final AgentContext context;
    private final Supplier<SessionContext> sessions;

    public ToolRunner(AgentContext context, Supplier<SessionContext> sessions) {
        this.context = context;
        this.sessions = sessions;
    }

    public ToolResult execute(ToolSpec spec, String callId, JsonNode input) {
        JsonNode original = input == null ? Json.object() : input;
        String inputJson = Json.write(original);
        LOG.debug("tool execute start name={} call={} kind={} thread={} input={}",
                spec.name(), callId, spec.kind(), context.threadId(),
                inputJson.length() <= 800 ? inputJson : inputJson.substring(0, 800) + "...");
        try {
            UserQuestions.Questions questions = spec.kind() == ToolCatalog.Kind.QUESTION
                    ? UserQuestions.parse(original) : null;
            ControlPlane.Decision decision = approval(spec, callId, original);
            LOG.debug("approval resolved tool={} call={} allowed={} updated={} external={}",
                    spec.name(), callId, decision.allowed(),
                            decision.updatedInput() != null, decision.externalResult() != null);
            if (context.isCancelled()) {
                return ToolResult.failure("Tool call interrupted.");
            }
            if (!decision.allowed()) {
                return ToolResult.failure(decision.denyMessage());
            }
            JsonNode effective = decision.updatedInput() == null || decision.updatedInput().isNull()
                    ? original : decision.updatedInput();
            JsonNode external = decision.externalResult();
            if (external != null && !external.isNull()) {
                if (spec.kind() == ToolCatalog.Kind.QUESTION) {
                    return UserQuestions.answers(questions, external);
                }
                return externalResult(external);
            }
            if (spec.kind() == ToolCatalog.Kind.QUESTION) {
                return ToolResult.failure("Missing user question result");
            }
            JsonNode changed = effective.equals(original) ? null : effective;
            ToolResult result;
            try {
                result = dispatch(spec, effective);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.warn("tool dispatch failed: tool=" + spec.name() + " thread="
                        + context.threadId() + " error=" + e.getMessage());
                return ToolResult.failure(context.isCancelled()
                        ? "Tool call interrupted."
                        : "Remote tool outcome is unknown. Do not retry blindly; verify external state before repeating operations with side effects.");
            }
            if (spec.kind() == ToolCatalog.Kind.MCP && !result.error()) {
                result = persistIfLarge(result);
            }
            LOG.debug("tool dispatch succeeded tool={} call={} error={} text_length={}",
                    spec.name(), callId, result.error(), result.text().length());
            if (changed == null) {
                return result;
            }
            return result.withPrefix("OMA approval changed " + spec.name() + " arguments to "
                    + Json.write(changed) + ". The returned result is for these approved arguments.");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private ControlPlane.Decision approval(ToolSpec spec, String callId, JsonNode input) {
        SessionContext session = sessions.get();
        String permission = ToolCatalog.permission(session, spec);
        LOG.debug("tool permission tool={} key={} permission={}",
                spec.name(), spec.permissionKey(), permission);
        if (ToolCatalog.PERMISSION_ALLOW.equals(permission)) {
            return ControlPlane.Decision.allow(input);
        }
        LOG.info("requesting approval tool={} call={} permission={}",
                spec.name(), callId, permission);
        return context.requestApproval(spec.name(), callId, input, APPROVAL_TIMEOUT);
    }

    private ToolResult dispatch(ToolSpec spec, JsonNode input) {
        return switch (spec.kind()) {
            case SANDBOX -> context.callTool(spec.remoteName(), input, SANDBOX_TIMEOUT, context.threadId());
            case WEB -> context.webTool(spec.remoteName(), input, context.threadId());
            case PORT -> context.portUrl(port(input), context.threadId());
            case QUESTION -> ToolResult.failure("Missing user question result");
            case MCP -> context.mcpTool(spec.remoteName(), input, context.threadId());
        };
    }

    private ToolResult persistIfLarge(ToolResult result) {
        String text = result.text();
        int length = text.codePointCount(0, text.length());
        if (length <= TOOL_OUTPUT_THRESHOLD) {
            return result;
        }
        try {
            String path = context.persistToolOutput(text, context.threadId());
            String preview = preview(text);
            String wrapped = "<persisted-output>Output too large (" + length
                    + " characters). Full output saved to: " + path
                    + "\nPreview (head and tail):\n" + preview + "\n</persisted-output>";
            return ToolResult.text(wrapped);
        } catch (RuntimeException e) {
            LOG.warn("mcp output persistence failed: {}", e.getMessage());
            return ToolResult.failure("remote call completed and was not retried: output could not be saved");
        }
    }

    private static String preview(String text) {
        int length = text.length();
        if (length <= TOOL_OUTPUT_PREVIEW * 2) {
            return text;
        }
        return text.substring(0, TOOL_OUTPUT_PREVIEW)
                + "... [preview middle omitted] ..."
                + text.substring(length - TOOL_OUTPUT_PREVIEW);
    }

    private static int port(JsonNode input) {
        JsonNode value = input == null ? null : input.get("port");
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("port must be an integer between 1 and 65535");
        }
        int port = value.asInt();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be an integer between 1 and 65535");
        }
        return port;
    }

    private static ToolResult externalResult(JsonNode external) {
        boolean error = external.hasNonNull("isError") && external.get("isError").asBoolean(false);
        String text = "";
        JsonNode blocks = external.get("content");
        if (blocks != null && blocks.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode block : blocks) {
                if (!"text".equals(Json.text(block, "type"))) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(Json.text(block, "text"));
            }
            text = builder.toString();
        }
        return error ? ToolResult.failure(text) : ToolResult.text(text);
    }
}
