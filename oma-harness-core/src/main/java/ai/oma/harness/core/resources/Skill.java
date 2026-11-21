package ai.oma.harness.core.resources;

/** 服务端下发的一个 skill 定义（名称、描述、指令内容），供资源加载器注入到 agent 上下文。 */
public record Skill(
        String name,
        String description,
        String path,
        String content,
        boolean modelInvocable,
        String provider) {
}
