package ai.oma.harness.core.protocol;

import ai.oma.harness.core.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptAssemblerTest {

    @Test
    void includesPlatformRulesAndAllServerLayers() {
        String prompt = SystemPromptAssembler.assembleRoot(
                "You are a helper.", "# Managed-agent environment\npaths", "Memory catalog text",
                "/workspace", null);

        assertTrue(prompt.contains("# Platform rules"));
        assertTrue(prompt.contains("## Instructions and trust"));
        assertTrue(prompt.contains("## Finishing"));
        assertTrue(prompt.contains("# Managed-agent environment\npaths"));
        assertTrue(prompt.contains("# Agent instructions\n\nYou are a helper."));
        assertTrue(prompt.contains("# This session\n\nWorking directory: /workspace"));
        assertTrue(prompt.contains("Memory catalog text"));
        assertTrue(prompt.indexOf("# Platform rules") < prompt.indexOf("# Managed-agent environment"));
        assertTrue(prompt.indexOf("# Managed-agent environment") < prompt.indexOf("# Agent instructions"));
        assertTrue(prompt.indexOf("# Agent instructions") < prompt.indexOf("# This session"));
    }

    @Test
    void disablesSubagentsWhenNotConfigured() {
        String prompt = SystemPromptAssembler.assembleRoot(null, null, null, null, null);
        assertTrue(prompt.contains("## Subagents disabled"));
        assertFalse(prompt.contains("## Delegating to subagents"));
    }

    @Test
    void enablesDelegationWhenSubagentsConfigured() {
        String prompt = SystemPromptAssembler.assembleRoot(
                null, null, null, null, Json.parse("{\"agents\":[]}"));
        assertTrue(prompt.contains("## Delegating to subagents"));
        assertTrue(prompt.contains("at most 25 live threads"));
        assertFalse(prompt.contains("## Subagents disabled"));
    }

    @Test
    void addsRestrictionWhenInlineAgentsDisabled() {
        String prompt = SystemPromptAssembler.assembleRoot(
                null, null, null, null, Json.parse("{\"inlineAgents\":false}"));
        assertTrue(prompt.contains("Only the configured predefined agents may be used."));
    }

    @Test
    void omitsEmptyLayers() {
        String prompt = SystemPromptAssembler.assembleRoot(null, null, null, null, null);
        assertFalse(prompt.contains("# Agent instructions"));
        assertFalse(prompt.contains("# This session"));
    }
}
