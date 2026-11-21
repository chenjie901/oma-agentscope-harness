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

public interface AgentContext {

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
