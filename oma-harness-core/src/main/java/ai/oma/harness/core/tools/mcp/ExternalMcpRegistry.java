package ai.oma.harness.core.tools.mcp;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP server 注册表：根据会话下发的 MCP 配置连接 server、发现工具并维护生命周期，
 * 把工具暴露给 ToolCatalog。由 core 在 refreshMcp 时调用。
 */
public final class ExternalMcpRegistry implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalMcpRegistry.class);

    private final OmaConfig config;
    private final Map<String, ExternalMcpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, McpToolBinding> bindings = new ConcurrentHashMap<>();
    private volatile String signature = "";

    public ExternalMcpRegistry(OmaConfig config) {
        this.config = config;
    }

    public synchronized void refresh(SessionContext context, String threadId) {
        JsonNode mcpConfig = context == null ? null : context.mcpConfig();
        String nextSignature = McpServerConfig.signature(mcpConfig);
        if (nextSignature.equals(signature)) {
            return;
        }
        List<McpServerConfig> servers;
        try {
            servers = McpServerConfig.parse(mcpConfig);
        } catch (RuntimeException e) {
            LOG.warn("mcp config rejected: {}", e.getMessage());
            throw e;
        }
        closeClients();
        clients.clear();
        bindings.clear();
        if (servers.isEmpty()) {
            signature = nextSignature;
            return;
        }
        List<CompletableFuture<Void>> discoveries = new ArrayList<>();
        for (McpServerConfig server : servers) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> discover(server, threadId));
            discoveries.add(future);
        }
        CompletableFuture.allOf(discoveries.toArray(CompletableFuture[]::new)).join();
        signature = nextSignature;
        LOG.info("mcp registry synchronized servers={} tools={}", clients.size(), bindings.size());
    }

    private void discover(McpServerConfig server, String threadId) {
        ExternalMcpClient client = new ExternalMcpClient(server, config);
        try {
            client.connect(threadId);
            List<McpToolBinding> tools = client.listTools(threadId);
            clients.put(server.name(), client);
            for (McpToolBinding tool : tools) {
                McpToolBinding previous = bindings.putIfAbsent(tool.displayName(), tool);
                if (previous != null) {
                    LOG.warn("mcp duplicate tool name {} from {} and {}",
                            tool.displayName(), previous.serverName(), tool.serverName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("mcp discovery failed server={} prefix={} error={}",
                    server.name(), server.prefix(), e.getMessage());
            try {
                client.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public List<McpToolBinding> bindings() {
        List<McpToolBinding> sorted = new ArrayList<>(bindings.values());
        sorted.sort((left, right) -> left.displayName().compareTo(right.displayName()));
        return List.copyOf(sorted);
    }

    public McpToolBinding binding(String displayName) {
        return bindings.get(displayName);
    }

    public ToolResult call(String displayName, JsonNode arguments, String threadId) {
        McpToolBinding binding = bindings.get(displayName);
        if (binding == null) {
            return ToolResult.failure("unknown MCP tool: " + displayName);
        }
        ExternalMcpClient client = clients.get(binding.serverName());
        if (client == null) {
            return ToolResult.failure("MCP server unavailable: " + binding.serverName());
        }
        if (client.authFailed()) {
            return ToolResult.failure("MCP authentication failed for " + binding.serverName());
        }
        ToolResult result = client.callTool(binding, arguments, threadId);
        if (client.sessionRejected()) {
            if (reconnect(client, binding.serverName(), threadId)) {
                result = client.callTool(binding, arguments, threadId);
            }
        }
        return result;
    }

    private boolean reconnect(ExternalMcpClient client, String serverName, String threadId) {
        McpServerConfig server = client.server();
        LOG.debug("mcp reconnecting server={} after session rejection", serverName);
        client.close();
        ExternalMcpClient replacement = new ExternalMcpClient(server, config);
        try {
            replacement.connect(threadId);
            List<McpToolBinding> tools = replacement.listTools(threadId);
            Map<String, McpToolBinding> observed = new LinkedHashMap<>();
            for (McpToolBinding tool : tools) {
                observed.put(tool.displayName(), tool);
            }
            for (McpToolBinding existing : bindings.values()) {
                if (!serverName.equals(existing.serverName())) {
                    continue;
                }
                McpToolBinding current = observed.get(existing.displayName());
                if (current == null || !sameSchema(existing, current)) {
                    LOG.warn("mcp schema changed during reconnect server={} tool={}", serverName, existing.displayName());
                    return false;
                }
            }
            clients.put(serverName, replacement);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("mcp reconnect failed server={} error={}", serverName, e.getMessage());
            replacement.close();
            return false;
        }
    }

    private static boolean sameSchema(McpToolBinding left, McpToolBinding right) {
        return left.remoteName().equals(right.remoteName())
                && left.inputSchema().equals(right.inputSchema());
    }

    private void closeClients() {
        for (ExternalMcpClient client : clients.values()) {
            try {
                client.close();
            } catch (RuntimeException e) {
                LOG.debug("mcp close failed server={} error={}", client.server().name(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closeClients();
        clients.clear();
        bindings.clear();
    }
}
