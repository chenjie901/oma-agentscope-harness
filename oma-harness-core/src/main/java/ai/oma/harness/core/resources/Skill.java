package ai.oma.harness.core.resources;

public record Skill(
        String name,
        String description,
        String path,
        String content,
        boolean modelInvocable,
        String provider) {
}
