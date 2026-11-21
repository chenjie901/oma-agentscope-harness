package ai.oma.harness.core.agent;

import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.protocol.WorkerMetadata;
import ai.oma.harness.core.resources.Skill;
import ai.oma.harness.core.tools.ToolResult;
import ai.oma.harness.core.tools.mcp.McpToolBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;

/**
 * Agent 与 OMA 服务端交互的上下文接口，由 core 的 worker 实现并注入到
 * {@link HarnessAgent#start(AgentContext)}。接入方通常不需要直接实现或使用它，
 * 只在自定义 prompt/工具策略时才需要了解其中的能力（事件发射、工具调用、审批、历史恢复等）。
 */
public interface AgentContext {

    /** 当前 code session id。 */
    String sessionId();

    String threadId();

    void emit(CanonicalEvent event);

    void emitNative(JsonNode payload);

    ToolResult callTool(String tool, JsonNode input, Duration timeout);

    ToolResult callTool(String tool, JsonNode input, Duration timeout, String threadId);

    ToolResult webTool(String remoteName, JsonNode input, String threadId);

    ToolResult portUrl(int port, String threadId);

    default List<McpToolBinding> mcpTools() {
        return List.of();
    }

    default ToolResult mcpTool(String displayName, JsonNode input, String threadId) {
        return ToolResult.failure("MCP tools are not available");
    }

    default String persistToolOutput(String content, String threadId) {
        throw new UnsupportedOperationException("tool output persistence is not available");
    }

    default void refreshMcp(SessionContext session, String threadId) {
    }

    default ai.oma.harness.core.observability.DiagnosticsReporter diagnostics() {
        return null;
    }

    default ai.oma.harness.core.observability.OtlpExporter otlp() {
        return null;
    }

    default ai.oma.harness.core.attachments.AttachmentStore attachments() {
        return null;
    }

    ModelReply chat(ModelRequest request);

    List<Skill> skills();

    boolean isCancelled();

    List<Transcript.RestoredMessage> restoredMessages();

    WorkerMetadata metadata();

    void persistInternal(ObjectNode payload);

    ControlPlane.Decision requestApproval(String toolName, String toolUseId, JsonNode input, Duration timeout);
}
