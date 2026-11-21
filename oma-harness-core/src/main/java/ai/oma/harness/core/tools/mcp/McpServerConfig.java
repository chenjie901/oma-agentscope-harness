package ai.oma.harness.core.tools.mcp;

import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record McpServerConfig(String name, String prefix, String type, URI url,
                              Map<String, String> headers, Set<String> disabledTools) {

    public static final String TYPE_HTTP = "http";
    public static final String TYPE_STREAMABLE_HTTP = "streamable-http";
    public static final String TYPE_SSE = "sse";

    private static final Set<String> TYPES = Set.of(TYPE_HTTP, TYPE_STREAMABLE_HTTP, TYPE_SSE);

    public boolean isSse() {
        return TYPE_SSE.equals(type);
    }

    public String toolName(String remoteName) {
        return "mcp__" + prefix + "__" + remoteName;
    }

    public static List<McpServerConfig> parse(JsonNode mcpConfig) {
        if (mcpConfig == null || mcpConfig.isNull()) {
            return List.of();
        }
        if (!mcpConfig.isObject()) {
            throw invalid("mcp_config must be an object");
        }
        JsonNode servers = mcpConfig.get("mcpServers");
        if (servers == null || servers.isNull()) {
            return List.of();
        }
        if (!servers.isObject()) {
            throw invalid("mcpServers must be an object");
        }
        List<McpServerConfig> parsed = new java.util.ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        servers.fields().forEachRemaining(entry -> {
            McpServerConfig server = parseServer(entry.getKey(), entry.getValue());
            if (!prefixes.add(server.prefix())) {
                throw invalid("ambiguous external MCP server name: " + entry.getKey());
            }
            parsed.add(server);
        });
        return List.copyOf(parsed);
    }

    private static McpServerConfig parseServer(String name, JsonNode node) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            throw invalid("Invalid external MCP server name length");
        }
        if (node == null || !node.isObject()) {
            throw invalid("external MCP server must be an object: " + name);
        }
        String type = text(node, "type");
        if (type == null || !TYPES.contains(type)) {
            throw invalid("Unsupported external MCP transport for " + name);
        }
        String rawUrl = text(node, "url");
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalid("Missing external MCP URL for " + name);
        }
        URI url;
        try {
            url = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid external MCP URL for " + name);
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalid("Invalid external MCP URL for " + name);
        }
        if (url.getRawUserInfo() != null || url.getFragment() != null) {
            throw invalid("Invalid external MCP URL for " + name);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = node.get("headers");
        if (headerNode != null && !headerNode.isNull()) {
            if (!headerNode.isObject()) {
                throw invalid("Invalid external MCP headers for " + name);
            }
            headerNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw invalid("Invalid external MCP headers for " + name);
                }
                headers.put(entry.getKey(), entry.getValue().asText());
            });
        }
        Set<String> disabled = new LinkedHashSet<>();
        JsonNode tools = node.get("tools");
        if (tools != null && !tools.isNull()) {
            if (!tools.isArray()) {
                throw invalid("Invalid external MCP tool configuration for " + name);
            }
            for (JsonNode tool : tools) {
                if (tool == null || !tool.isObject()) {
                    throw invalid("Invalid external MCP tool configuration for " + name);
                }
                String toolName = text(tool, "name");
                if (toolName == null || toolName.isBlank()) {
                    throw invalid("Invalid external MCP tool configuration for " + name);
                }
                JsonNode enabled = tool.get("enabled");
                if (enabled != null && !enabled.isNull() && !enabled.isBoolean()) {
                    throw invalid("Invalid external MCP tool configuration for " + name);
                }
                if (enabled != null && !enabled.asBoolean(true)) {
                    disabled.add(toolName);
                }
            }
        }
        String prefix = prefix(name);
        if (!prefix.matches("[a-zA-Z0-9_-]+")) {
            throw invalid("Invalid or ambiguous external MCP server name");
        }
        return new McpServerConfig(name, prefix, type, url, Map.copyOf(headers), Set.copyOf(disabled));
    }

    public static String prefix(String name) {
        boolean simple = name.chars().allMatch(c ->
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-');
        if (simple) {
            return name.replace('.', '_');
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return "mcp_" + HexFormat.of().formatHex(digest, 0, 12);
    }

    public static String signature(JsonNode mcpConfig) {
        return mcpConfig == null || mcpConfig.isNull() ? "" : Json.write(mcpConfig);
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        return child.asText();
    }

    private static OmaHttpException invalid(String message) {
        return new OmaHttpException(200, "invalid_mcp_config", message);
    }
}
