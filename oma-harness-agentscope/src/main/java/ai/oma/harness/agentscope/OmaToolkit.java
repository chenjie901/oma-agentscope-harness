package ai.oma.harness.agentscope;

import ai.oma.harness.core.agent.AgentContext;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.tools.ToolCatalog;
import ai.oma.harness.core.tools.ToolCatalog.ToolSpec;
import ai.oma.harness.core.tools.ToolResult;
import ai.oma.harness.core.tools.ToolRunner;
import ai.oma.harness.core.tools.mcp.McpToolBinding;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OmaToolkit {
    private static final Logger LOG = LoggerFactory.getLogger(OmaToolkit.class);
    private final Toolkit toolkit = new Toolkit();
    private final ToolRunner runner;
    private final Map<String, AgentTool> registered = new LinkedHashMap<>();

    public OmaToolkit(AgentContext context, Supplier<SessionContext> sessions) {
        this.runner = new ToolRunner(context, sessions);
    }

    public Toolkit toolkit() {
        return toolkit;
    }

    public synchronized void sync(SessionContext context) {
        sync(context, List.of());
    }

    public synchronized void sync(SessionContext context, List<McpToolBinding> mcpTools) {
        List<ToolSpec> enabled = ToolCatalog.enabled(context, mcpTools);
        Map<String, ToolSpec> keep = new LinkedHashMap<>();
        for (ToolSpec spec : enabled) {
            keep.put(spec.name(), spec);
        }
        for (Map.Entry<String, AgentTool> entry : List.copyOf(registered.entrySet())) {
            if (!keep.containsKey(entry.getKey())) {
                toolkit.removeTool(entry.getKey());
                registered.remove(entry.getKey());
            }
        }
        for (ToolSpec spec : enabled) {
            if (!registered.containsKey(spec.name())) {
                AgentTool tool = new OmaAgentTool(spec, runner);
                toolkit.registerAgentTool(tool);
                registered.put(spec.name(), tool);
            }
        }
        LOG.info("toolkit synchronized enabled={} tools={}",
                enabled.size(), registered.keySet());
    }

    private static final class OmaAgentTool implements AgentTool {
        private final ToolSpec spec;
        private final ToolRunner runner;

        private OmaAgentTool(ToolSpec spec, ToolRunner runner) {
            this.spec = spec;
            this.runner = runner;
        }

        @Override
        public String getName() {
            return spec.name();
        }

        @Override
        public String getDescription() {
            return spec.description();
        }

        @Override
        public Map<String, Object> getParameters() {
            return spec.schema();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> execute(param));
        }

        private ToolResultBlock execute(ToolCallParam param) {
            ToolUseBlock toolUse = param.getToolUseBlock();
            String toolCallId = toolUse == null ? "" : toolUse.getId();
            Map<String, Object> arguments = toolUse == null ? null : toolUse.getInput();
            if (arguments == null || arguments.isEmpty()) {
                arguments = param.getInput();
            }
            JsonNode input = Json.mapper().valueToTree(arguments == null ? Map.of() : arguments);
            ToolResult result = runner.execute(spec, toolCallId, input);
            String text = result.text();
            if (text.isBlank()) {
                text = result.errorCode() == null ? "tool execution failed" : result.errorCode();
            }
            List<ContentBlock> output = List.of(TextBlock.builder().text(text).build());
            ToolResultState state = result.error() ? ToolResultState.ERROR : ToolResultState.SUCCESS;
            return new ToolResultBlock(toolCallId, spec.name(), output, Map.of(), state);
        }
    }
}
