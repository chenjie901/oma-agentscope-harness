package ai.oma.harness.core.claim;

import ai.oma.harness.core.OmaConfig;
import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.OmaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SessionContext {
    private static final Logger LOG = LoggerFactory.getLogger(SessionContext.class);
    public static final String BACKEND_MESSAGES = "messages";
    public static final String BACKEND_CHAT_COMPLETIONS = "chat_completions";
    private static final Set<String> PERMISSIONS = Set.of("allow", "ask", "deny");

    private final String model;
    private final String apiBackend;
    private final String systemPrompt;
    private final String cwd;
    private final List<String> runtimeTools;
    private final Map<String, String> toolPermissions;
    private final JsonNode mcpConfig;
    private final JsonNode subagents;

    private SessionContext(String model, String apiBackend, String systemPrompt, String cwd,
                           List<String> runtimeTools, Map<String, String> toolPermissions,
                           JsonNode mcpConfig, JsonNode subagents) {
        this.model = model;
        this.apiBackend = apiBackend;
        this.systemPrompt = systemPrompt;
        this.cwd = cwd;
        this.runtimeTools = runtimeTools;
        this.toolPermissions = toolPermissions;
        this.mcpConfig = mcpConfig;
        this.subagents = subagents;
    }

    public static SessionContext of(String model, String apiBackend, String systemPrompt, String cwd,
                                    List<String> runtimeTools, Map<String, String> toolPermissions) {
        return new SessionContext(model, apiBackend, systemPrompt, cwd,
                runtimeTools == null ? List.of() : List.copyOf(runtimeTools),
                toolPermissions == null ? Map.of() : Map.copyOf(toolPermissions),
                Json.object(), null);
    }

    public static SessionContext load(OmaConfig config) {
        return fetch(config, null);
    }

    public static SessionContext refresh(OmaConfig config, String threadId) {
        return fetch(config, threadId);
    }

    public String model() {
        return model;
    }

    public String apiBackend() {
        return apiBackend;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String cwd() {
        return cwd;
    }

    public List<String> runtimeTools() {
        return runtimeTools;
    }

    public Map<String, String> toolPermissions() {
        return toolPermissions;
    }

    public JsonNode mcpConfig() {
        return mcpConfig;
    }

    public JsonNode subagents() {
        return subagents;
    }

    private static SessionContext fetch(OmaConfig config, String threadId) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(config.apiBase() + "v2/sessions/" + config.sessionId()))
                .header("Authorization", "Bearer " + config.ingressToken())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();
        String thread = threadId == null ? "" : threadId.trim();
        if (!thread.isEmpty() && !"root".equals(thread)) {
            builder.header("X-OMA-Session-Thread-ID", thread);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                LOG.warn("session context HTTP {} thread={}", response.statusCode(), thread);
                throw new OmaHttpException(response.statusCode(), "session_context_failed", response.body());
            }
            SessionContext context = parse(Json.parse(response.body()));
            LOG.debug("session context loaded thread={} model={} backend={} tools={} permissions={}",
                    thread, context.model(), context.apiBackend(),
                    context.runtimeTools(), context.toolPermissions());
            return context;
        } catch (IOException e) {
            throw new OmaHttpException(0, "transport_error", "session context request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmaHttpException(0, "interrupted", "session context request interrupted");
        }
    }

    private static SessionContext parse(JsonNode body) {
        JsonNode context = body.get("session_context");
        if (context == null || !context.isObject()) {
            throw new OmaHttpException(200, "invalid_session_context", "response has no session_context");
        }
        String model = Json.text(context, "model");
        if (model == null || model.isBlank()) {
            throw new OmaHttpException(200, "invalid_session_context", "session_context has no model");
        }
        return new SessionContext(
                model,
                apiBackend(context, model),
                systemPrompt(context),
                Json.text(context, "cwd"),
                runtimeTools(context.get("runtime_tools")),
                toolPermissions(context.get("runtime_tool_permissions")),
                objectOrEmpty(context.get("mcp_config")),
                objectOrNull(context.get("subagents")));
    }

    private static List<String> runtimeTools(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new OmaHttpException(200, "invalid_session_context", "runtime_tools must be an array");
        }
        List<String> tools = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.isTextual()) {
                throw new OmaHttpException(200, "invalid_session_context", "runtime_tools entries must be strings");
            }
            tools.add(entry.asText());
        }
        return List.copyOf(tools);
    }

    private static Map<String, String> toolPermissions(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new OmaHttpException(200, "invalid_session_context", "runtime_tool_permissions must be an object");
        }
        Map<String, String> permissions = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || !PERMISSIONS.contains(entry.getValue().asText())) {
                throw new OmaHttpException(200, "invalid_session_context",
                        "runtime_tool_permissions has an unknown value for " + entry.getKey());
            }
            permissions.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(permissions);
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node == null || !node.isObject() ? Json.object() : node;
    }

    private static JsonNode objectOrNull(JsonNode node) {
        return node == null || !node.isObject() ? null : node;
    }

    private static String apiBackend(JsonNode context, String model) {
        JsonNode entry = lookup(context.get("models"), model);
        if (entry == null) {
            return BACKEND_MESSAGES;
        }
        String backend = Json.text(entry, "api_backend");
        return backend == null || backend.isBlank() ? BACKEND_MESSAGES : backend;
    }

    private static JsonNode lookup(JsonNode models, String model) {
        if (models == null || models.isNull()) {
            return null;
        }
        if (models.isObject()) {
            JsonNode entry = models.get(model);
            if (entry != null) {
                return entry;
            }
            int slash = model.lastIndexOf('/');
            return slash < 0 ? null : models.get(model.substring(slash + 1));
        }
        for (JsonNode item : models) {
            if (model.equals(Json.text(item, "id"))) {
                return item;
            }
        }
        return null;
    }

    private static String systemPrompt(JsonNode context) {
        String base = Json.text(context, "system_prompt");
        if (base == null || base.isBlank()) {
            base = Json.text(context, "custom_system_prompt");
        }
        String appended = Json.text(context, "append_system_prompt");
        if (appended == null || appended.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return appended;
        }
        return base + "\n\n" + appended;
    }
}
