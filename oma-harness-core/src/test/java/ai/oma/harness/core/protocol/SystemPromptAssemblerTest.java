package ai.oma.harness.core.protocol;

import ai.oma.harness.core.claim.SessionContext;
import ai.oma.harness.core.resources.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptAssemblerTest {
    private final SessionContext session = SessionContext.of("m", SessionContext.BACKEND_MESSAGES,
            "instructions", "/work", List.of(), java.util.Map.of());

    @Test
    void injectsSkillCatalogForModelInvocableSkills() {
        Skill sk = new Skill("pdf", "Read and summarize PDF files", "p", "full pdf skill", true, null);
        Skill hidden = new Skill("secret", "hidden", "p", "x", false, null);

        String prompt = SystemPromptAssembler.assembleRoot(session, List.of(sk, hidden));

        assertTrue(prompt.contains("# Available skills"));
        assertTrue(prompt.contains("- pdf: Read and summarize PDF files"));
        assertTrue(prompt.contains("load_skill"));
        assertFalse(prompt.contains("secret"));
    }

    @Test
    void noSkillSectionWhenEmptyOrAllNotModelInvocable() {
        Skill hidden = new Skill("secret", "hidden", "p", "x", false, null);
        String empty = SystemPromptAssembler.assembleRoot(session, List.of());
        String allHidden = SystemPromptAssembler.assembleRoot(session, List.of(hidden));
        assertFalse(empty.contains("# Available skills"));
        assertFalse(allHidden.contains("# Available skills"));
    }

    @Test
    void legacyOverloadRemainsCompatible() {
        String prompt = SystemPromptAssembler.assembleRoot(session);
        assertTrue(prompt.contains("# Platform rules"));
        assertTrue(prompt.contains("instructions"));
    }
}
