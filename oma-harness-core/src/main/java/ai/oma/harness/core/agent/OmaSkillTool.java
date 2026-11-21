package ai.oma.harness.core.agent;

import ai.oma.harness.core.resources.Skill;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 框架内置工具：按 skill 名称加载 SKILL.md 正文，对齐 DSH 的 ToolSkill 语义。 */
public final class OmaSkillTool implements AgentTool {
    public static final String NAME = "load_skill";
    private static final String DESCRIPTION = "Load the full instructions of a named skill that is available "
            + "in this session. Use it when a task matches a listed skill and you need its detailed guidance. "
            + "Pass the exact skill name from the available-skills catalog.";

    private final Supplier<Map<String, Skill>> skills;

    public OmaSkillTool(Supplier<Map<String, Skill>> skills) {
        this.skills = skills;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of(
                        "type", "string",
                        "description", "Exact name of the skill to load")),
                "required", List.of("name"));
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        ToolUseBlock toolUse = param.getToolUseBlock();
        String toolCallId = toolUse == null ? "" : toolUse.getId();
        Map<String, Object> input = toolUse == null ? null : toolUse.getInput();
        if (input == null || input.isEmpty()) {
            input = param.getInput();
        }
        String name = input == null ? null : String.valueOf(input.get("name"));
        Map<String, Skill> current = skills.get();
        Skill skill = name == null || current == null ? null : current.get(name);
        String text;
        ToolResultState state;
        if (skill == null) {
            text = "Skill not found: " + name;
            state = ToolResultState.ERROR;
        } else {
            String content = skill.content() == null || skill.content().isBlank()
                    ? "(skill has no instructions)" : skill.content();
            text = "# " + skill.name() + "\n\n" + content;
            state = ToolResultState.SUCCESS;
        }
        List<ContentBlock> output = List.of(TextBlock.builder().text(text).build());
        return new ToolResultBlock(toolCallId, NAME, output, Map.of(), state);
    }
}
