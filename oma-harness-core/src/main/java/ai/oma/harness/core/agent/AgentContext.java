package ai.oma.harness.core.agent;

import ai.oma.harness.core.model.ModelReply;
import ai.oma.harness.core.model.ModelRequest;
import ai.oma.harness.core.protocol.CanonicalEvent;
import ai.oma.harness.core.protocol.ControlPlane;
import ai.oma.harness.core.protocol.Transcript;
import ai.oma.harness.core.protocol.WorkerMetadata;
import ai.oma.harness.core.resources.Skill;
import ai.oma.harness.core.tools.ToolResult;
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

    ModelReply chat(ModelRequest request);

    List<Skill> skills();

    boolean isCancelled();

    List<Transcript.RestoredMessage> restoredMessages();

    WorkerMetadata metadata();

    void persistInternal(ObjectNode payload);

    ControlPlane.Decision requestApproval(String toolName, String toolUseId, JsonNode input, Duration timeout);
}
