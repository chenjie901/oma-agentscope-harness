package ai.oma.harness.core.agent;

import ai.oma.harness.core.resources.Skill;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmaSkillToolTest {

    @Test
    void returnsSkillContentByName() {
        Skill skill = new Skill("pdf", "desc", "p", "FULL PDF INSTRUCTIONS", true, null);
        OmaSkillTool tool = new OmaSkillTool(() -> Map.of("pdf", skill));

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .input(Map.of("name", "pdf"))
                .build()).block();

        assertEquals(ToolResultState.SUCCESS, result.getState());
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(text.contains("FULL PDF INSTRUCTIONS"));
        assertTrue(text.contains("# pdf"));
    }

    @Test
    void returnsErrorForUnknownSkill() {
        OmaSkillTool tool = new OmaSkillTool(Map::of);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .input(Map.of("name", "missing"))
                .build()).block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("Skill not found: missing", ((TextBlock) result.getOutput().get(0)).getText());
    }

    @Test
    void declaresNameAndSchema() {
        OmaSkillTool tool = new OmaSkillTool(Map::of);
        assertEquals("load_skill", tool.getName());
        assertTrue(tool.getParameters().containsKey("properties"));
        assertTrue(tool.isReadOnly());
    }
}
