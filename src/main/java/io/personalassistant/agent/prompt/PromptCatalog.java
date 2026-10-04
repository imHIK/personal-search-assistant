package io.personalassistant.agent.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.common.JsonConfigLoader;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PromptCatalog {

    private static final Logger LOG = Logger.getLogger(PromptCatalog.class.getName());

    static final String RESOURCE = "config/prompts.json";

    @ConfigProperty(name = "app.prompts.path")
    Optional<String> overridePath;

    private Map<String, PromptTemplate> prompts = Map.of();
    private Map<String, TaskSpec> tasks = Map.of();

    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Set<String> offerable = new LinkedHashSet<>();

    public static PromptCatalog bundled() {
        PromptCatalog catalog = new PromptCatalog();
        catalog.overridePath = Optional.empty();
        catalog.load();
        return catalog;
    }

    void onStart(@Observes StartupEvent event) {
        load();
    }

    void load() {
        JsonNode root = JsonConfigLoader.load(RESOURCE, overridePath);
        this.prompts = readPrompts(root);
        this.tasks = readTasks(root);
        validate();
        LOG.info("Prompt catalogue loaded: prompts=" + prompts.keySet() + ", tasks=" + tasks.keySet());
    }

    /** @throws NoSuchElementException if no prompt is filed under {@code id} */
    public PromptTemplate prompt(String id) {
        PromptTemplate template = prompts.get(id);
        if (template == null) {
            throw new NoSuchElementException("No prompt \"" + id + "\" in " + RESOURCE
                    + ". Available: " + prompts.keySet());
        }
        return template;
    }

    /** @throws NoSuchElementException if no task is filed under {@code id} */
    public TaskSpec task(String id) {
        TaskSpec spec = tasks.get(id);
        if (spec == null) {
            throw new NoSuchElementException("No task \"" + id + "\" in " + RESOURCE
                    + ". Available: " + tasks.keySet());
        }
        return spec;
    }

    public PromptTemplate promptForTask(String taskId) {
        return prompt(task(taskId).promptId());
    }

    public Set<String> promptIds() {
        return prompts.keySet();
    }

    public Set<String> taskIds() {
        return tasks.keySet();
    }

    public java.util.Collection<TaskSpec> tasks() {
        return tasks.values();
    }

    public String name(String taskId) {
        return names.getOrDefault(taskId, taskId);
    }

    public String description(String taskId) {
        return descriptions.getOrDefault(taskId, "");
    }

    public boolean offerableInDigest(String taskId) {
        return offerable.contains(taskId);
    }

    private Map<String, PromptTemplate> readPrompts(JsonNode root) {
        JsonNode node = root.path("prompts");
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " must define at least one entry under \"prompts\"");
        }
        Map<String, PromptTemplate> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode p = entry.getValue();
            String where = RESOURCE + " prompts." + id;
            out.put(id, new PromptTemplate(
                    id,
                    p.path("description").asText(""),
                    JsonConfigLoader.requiredText(p, "system", where),
                    p.path("user").asText(""),
                    textList(p.path("variables"))));
        });
        // Not Map.copyOf, which is unordered: the task list must keep file order across restarts.
        return Collections.unmodifiableMap(out);
    }

    private Map<String, TaskSpec> readTasks(JsonNode root) {
        JsonNode node = root.path("tasks");
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " must define at least one entry under \"tasks\"");
        }
        Map<String, TaskSpec> out = new LinkedHashMap<>();
        descriptions.clear();
        names.clear();
        offerable.clear();
        node.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode t = entry.getValue();
            String where = RESOURCE + " tasks." + id;
            out.put(id, new TaskSpec(
                    id,
                    t.path("prompt").asText(id),
                    t.path("llmProfile").asText("default"),
                    t.path("contextChars").asInt(0),
                    t.path("maxSources").asInt(0),
                    enumValue(TaskSpec.SourceText.class, t.path("sourceText"),
                            TaskSpec.SourceText.CHUNK, where + ".sourceText"),
                    enumValue(LlmProvider.ResponseFormat.class, t.path("responseFormat"),
                            LlmProvider.ResponseFormat.TEXT, where + ".responseFormat"),
                    t.path("annotates").asText(null)));
            descriptions.put(id, t.path("description").asText(""));
            names.put(id, t.path("name").asText(id));
            if (t.path("digest").asBoolean(false)) {
                offerable.add(id);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, E fallback, String where) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            return fallback;
        }
        String raw = node.asText().trim();
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new IllegalStateException(where + " is \"" + raw + "\"; expected one of "
                + java.util.Arrays.toString(type.getEnumConstants()));
    }

    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) {
                out.add(v.asText().trim());
            }
        });
        return List.copyOf(out);
    }

    private void validate() {
        List<String> problems = new ArrayList<>();

        for (TaskSpec task : tasks.values()) {
            if (!prompts.containsKey(task.promptId())) {
                problems.add("task \"" + task.id() + "\" references prompt \"" + task.promptId()
                        + "\", which does not exist (available: " + prompts.keySet() + ")");
            }
        }

        for (PromptTemplate prompt : prompts.values()) {
            List<String> used = prompt.placeholdersUsed();
            for (String name : used) {
                if (!prompt.variables().contains(name) && !CALLER_SUPPLIED.contains(name)) {
                    problems.add("prompt \"" + prompt.id() + "\" uses {{" + name
                            + "}} but does not declare it in \"variables\"");
                }
            }
            for (String declared : prompt.variables()) {
                if (!used.contains(declared)) {
                    problems.add("prompt \"" + prompt.id() + "\" declares variable \"" + declared
                            + "\" that its text never uses");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid " + RESOURCE + ":\n  - " + String.join("\n  - ", problems));
        }
    }

    private static final Set<String> CALLER_SUPPLIED = Set.of("query", "sources");
}
