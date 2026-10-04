package io.personalassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.agent.llm.LlmProfile;
import io.personalassistant.agent.llm.LlmProfiles;
import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.domain.model.Task;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class DefaultMetadataEnricher implements MetadataEnricher {

    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final LlmProvider llm;
    private final LlmProfiles profiles;
    private final TaskLibrary library;

    @Inject
    public DefaultMetadataEnricher(LlmProvider llm, LlmProfiles profiles, TaskLibrary library) {
        this.llm = llm;
        this.profiles = profiles;
        this.library = library;
    }

    @Override
    public Map<String, Object> enrich(Task task, String title, String text) {
        if (!task.metadata()) {
            throw new IllegalArgumentException("Task " + task.id() + " is not a metadata task");
        }
        TaskLibrary.ResolvedTask resolved = library.resolve(task);
        Map<String, String> values = new LinkedHashMap<>(resolved.variables());
        values.put("fence", AnswerPromptBuilder.FENCE);
        values.put("sources", sourceBlock(title, text, budget(task)));

        // Background work: wait for the window rather than fail, unless the profile says otherwise.
        LlmProfile profile = profiles.get(resolved.spec().llmProfile()).withDefaultMode(RateLimitMode.WAIT);
        String reply = llm.complete(profile, resolved.spec().responseFormat(),
                resolved.prompt().renderSystem(values),
                List.of(new LlmProvider.Message("user", resolved.prompt().renderUser(values))));

        JsonNode root = JsonReplies.object(reply)
                .orElseThrow(() -> new IllegalStateException("The model's reply was not a JSON object"));
        return coerce(task, root);
    }

    /** A 0 budget means unbounded elsewhere, but one long document would then exceed any model's context. */
    private static int budget(Task task) {
        return task.contextChars() > 0 ? task.contextChars() : Task.DEFAULT_CONTEXT_CHARS;
    }

    static String sourceBlock(String title, String text, int budget) {
        String body = text == null ? "" : text.strip();
        if (body.length() > budget) {
            String marker = AnswerPromptBuilder.TRUNCATION_MARKER;
            body = body.substring(0, Math.max(0, budget - marker.length())) + marker;
        }
        String fence = AnswerPromptBuilder.FENCE;
        String heading = title == null || title.isBlank() ? "" : title.strip() + "\n";
        return heading + fence + "\n" + body + "\n" + fence;
    }

    static Map<String, Object> coerce(Task task, JsonNode root) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Task.Field field : task.fields()) {
            Optional<Object> value = value(field, root.get(field.name()));
            if (value.isPresent()) {
                out.put(field.name(), value.get());
            } else if (!field.optional()) {
                missing.add(field.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The model's reply had no usable value for "
                    + String.join(", ", missing));
        }
        return out;
    }

    private static Optional<Object> value(Task.Field field, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        return switch (field.type()) {
            case NUMBER -> number(node);
            case BOOLEAN -> bool(node);
            case TEXT -> node.isValueNode() ? allowed(field, node.asText()) : Optional.empty();
            case LIST -> list(field, node);
        };
    }

    private static Optional<Object> number(JsonNode node) {
        if (node.isNumber()) {
            return Optional.of(node.isIntegralNumber() ? (Object) node.asLong() : (Object) node.asDouble());
        }
        // Models write "5+" or "3-5 years" even when asked for a number; the first figure is the intent.
        Matcher m = FIRST_NUMBER.matcher(node.asText(""));
        if (!m.find()) {
            return Optional.empty();
        }
        String figure = m.group();
        return Optional.of(figure.contains(".") ? (Object) Double.parseDouble(figure)
                : (Object) Long.parseLong(figure));
    }

    private static Optional<Object> bool(JsonNode node) {
        if (node.isBoolean()) {
            return Optional.of(node.asBoolean());
        }
        return switch (node.asText("").trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes" -> Optional.of(true);
            case "false", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static Optional<Object> list(Task.Field field, JsonNode node) {
        List<String> raw = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(element -> {
                if (element.isValueNode() && !element.isNull()) {
                    raw.add(element.asText());
                }
            });
        } else if (node.isValueNode()) {
            raw.addAll(List.of(node.asText().split(",")));
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String item : raw) {
            allowed(field, item).ifPresent(v -> kept.add((String) v));
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(kept));
    }

    /** With allowed values, a case-insensitive match maps to the declared spelling and anything else drops. */
    private static Optional<Object> allowed(Task.Field field, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (field.values().isEmpty()) {
            return Optional.of(trimmed);
        }
        return field.values().stream()
                .filter(v -> v.equalsIgnoreCase(trimmed))
                .findFirst()
                .map(v -> (Object) v);
    }
}
