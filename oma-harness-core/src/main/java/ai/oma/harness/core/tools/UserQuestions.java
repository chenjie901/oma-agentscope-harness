package ai.oma.harness.core.tools;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UserQuestions {
    public record Question(String id, List<String> labels, boolean multiSelect) {
    }

    public record Questions(List<Question> questions) {
    }

    private UserQuestions() {
    }

    public static Questions parse(JsonNode input) {
        JsonNode entries = input == null ? null : input.get("questions");
        if (entries == null || !entries.isArray() || entries.isEmpty()) {
            throw new IllegalArgumentException("Questions require non-empty ids and text");
        }
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : entries) {
            String id = Json.text(entry, "id");
            String question = Json.text(entry, "question");
            if (id == null || id.isBlank() || question == null || question.isBlank()) {
                throw new IllegalArgumentException("Questions require non-empty ids and text");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Question ids must be unique");
            }
            List<String> labels = new ArrayList<>();
            JsonNode options = entry.get("options");
            if (options != null && options.isArray()) {
                for (JsonNode option : options) {
                    String label = Json.text(option, "label");
                    if (label != null) {
                        labels.add(label);
                    }
                }
            }
            JsonNode multiSelect = entry.get("multi_select");
            questions.add(new Question(id, List.copyOf(labels), multiSelect != null && multiSelect.asBoolean(false)));
        }
        return new Questions(List.copyOf(questions));
    }

    public static ToolResult answers(Questions questions, JsonNode externalResult) {
        String text = "";
        boolean error = false;
        if (externalResult != null) {
            JsonNode blocks = externalResult.get("content");
            if (blocks != null && blocks.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode block : blocks) {
                    if (!"text".equals(Json.text(block, "type"))) {
                        continue;
                    }
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(Json.text(block, "text"));
                }
                text = builder.toString();
            }
            JsonNode isError = externalResult.get("isError");
            error = isError != null && isError.asBoolean(false);
        }
        if (error) {
            throw new IllegalArgumentException(text.isBlank() ? "User question was cancelled" : text);
        }
        JsonNode value;
        try {
            value = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid user question answers");
        }
        JsonNode validated = validate(questions, value);
        return new ToolResult(false, null,
                List.of(Map.of("type", "text", "text", Json.write(validated))), validated);
    }

    private static JsonNode validate(Questions questions, JsonNode value) {
        JsonNode answers = value == null ? null : value.get("answers");
        if (answers == null || !answers.isArray()) {
            throw new IllegalArgumentException("Invalid user question answers");
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode answer : answers) {
            String id = Json.text(answer, "id");
            JsonNode selected = answer.get("selected");
            if (id == null || id.isBlank() || selected == null || !selected.isArray()) {
                throw new IllegalArgumentException("Invalid user question answers");
            }
            for (JsonNode option : selected) {
                if (!option.isTextual()) {
                    throw new IllegalArgumentException("Invalid user question answers");
                }
            }
            JsonNode custom = answer.get("custom");
            if (custom != null && !custom.isNull() && !custom.isTextual()) {
                throw new IllegalArgumentException("Invalid user question answers");
            }
            if (byId.put(id, answer) != null) {
                throw new IllegalArgumentException("Answers must match each question exactly once");
            }
        }
        if (byId.size() != questions.questions().size()) {
            throw new IllegalArgumentException("Answers must match each question exactly once");
        }
        for (Question question : questions.questions()) {
            JsonNode answer = byId.get(question.id());
            if (answer == null) {
                throw new IllegalArgumentException("Answer references an unknown question id");
            }
            Set<String> selected = new LinkedHashSet<>();
            for (JsonNode option : answer.get("selected")) {
                if (!selected.add(option.asText())) {
                    throw new IllegalArgumentException("Selected options must be unique");
                }
            }
            for (String label : selected) {
                if (!question.labels().contains(label)) {
                    throw new IllegalArgumentException("Answer contains an unknown option");
                }
            }
            JsonNode custom = answer.get("custom");
            boolean hasCustom = custom != null && custom.isTextual() && !custom.asText().isEmpty();
            if (!question.multiSelect() && (selected.size() > 1 || (!selected.isEmpty() && hasCustom))) {
                throw new IllegalArgumentException(
                        "Single-select question allows at most one option or a custom answer, not both");
            }
        }
        return value;
    }
}
