package ai.oma.harness.core.tools;

import ai.oma.harness.core.claim.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {

    @Test
    void registersOnlyRuntimeTools() {
        SessionContext context = SessionContext.of("model", SessionContext.BACKEND_MESSAGES, null, null,
                List.of("Bash", "Read", "ask_user_question", "unknown_tool"),
                Map.of());

        List<String> names = ToolCatalog.enabled(context).stream().map(ToolCatalog.ToolSpec::name).toList();

        assertEquals(List.of("Bash", "Read", "AskUserQuestion"), names);
    }

    @Test
    void excludesDeniedTools() {
        SessionContext context = SessionContext.of("model", SessionContext.BACKEND_MESSAGES, null, null,
                List.of("Bash", "Read", "web_search"),
                Map.of("bash", "deny", "read", "allow"));

        List<String> names = ToolCatalog.enabled(context).stream().map(ToolCatalog.ToolSpec::name).toList();

        assertEquals(List.of("Read", "web_search"), names);
    }

    @Test
    void exposesNoToolsWithoutAgentToolset() {
        SessionContext context = SessionContext.of("model", SessionContext.BACKEND_MESSAGES, null, null,
                List.of(), Map.of("bash", "allow"));

        assertTrue(ToolCatalog.enabled(context).isEmpty());
        assertTrue(ToolCatalog.enabled(null).isEmpty());
    }

    @Test
    void normalizesNamesAcrossCasingAndSeparators() {
        assertEquals(ToolCatalog.normalize("AskUserQuestion"), ToolCatalog.normalize("ask_user_question"));
        assertEquals(ToolCatalog.normalize("web-search"), ToolCatalog.normalize("WEB_SEARCH"));
        assertEquals("getporturl", ToolCatalog.normalize("get_port_url"));
    }

    @Test
    void resolvesSpecsByModelAndRuntimeName() {
        assertEquals("Bash", ToolCatalog.byName("bash").name());
        assertEquals("AskUserQuestion", ToolCatalog.byName("ask_user_question").name());
        assertEquals("web_fetch", ToolCatalog.byName("web_fetch").name());
        assertNull(ToolCatalog.byName("mcp__weather__forecast"));
    }

    @Test
    void defaultsUnknownPermissionsToAsk() {
        SessionContext context = SessionContext.of("model", SessionContext.BACKEND_MESSAGES, null, null,
                List.of("Bash"), Map.of("bash", "allow"));
        ToolCatalog.ToolSpec bash = ToolCatalog.byName("Bash");
        ToolCatalog.ToolSpec read = ToolCatalog.byName("Read");

        assertEquals("allow", ToolCatalog.permission(context, bash));
        assertEquals("ask", ToolCatalog.permission(context, read));
        assertEquals("ask", ToolCatalog.permission(null, bash));
    }

    @Test
    void marksSandboxReadOnlyTools() {
        assertFalse(ToolCatalog.byName("Bash").readOnly());
        assertFalse(ToolCatalog.byName("Write").readOnly());
        assertFalse(ToolCatalog.byName("Edit").readOnly());
        assertTrue(ToolCatalog.byName("Read").readOnly());
        assertTrue(ToolCatalog.byName("Grep").readOnly());
        assertTrue(ToolCatalog.byName("Glob").readOnly());
        assertTrue(ToolCatalog.byName("get_port_url").readOnly());
        assertFalse(ToolCatalog.byName("AskUserQuestion").readOnly());
    }

    @Test
    void publishesSchemasWithRequiredFields() {
        Map<String, Object> schema = ToolCatalog.byName("Bash").schema();

        assertEquals("object", schema.get("type"));
        assertEquals(List.of("command"), schema.get("required"));
        assertEquals(false, ToolCatalog.byName("get_port_url").schema().get("additionalProperties"));
    }
}
