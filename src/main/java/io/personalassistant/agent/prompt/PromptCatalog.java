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

/**
 * The catalogue of prompts and the tasks that use them, loaded from {@code config/prompts.json}.
 *
 * <p>Replaces a prompt hardcoded in a Java method. A literal cannot be categorised, cannot be reused by a
 * second feature, cannot be overridden without a rebuild, and gives a user no way to supply their own —
 * all of which this project will want as reranking, rephrasing and summarisation arrive. Filing prompts
 * by task id makes each one a named thing that any caller can ask for.
 *
 * <p>Loading is eager and validation is a startup failure, matching {@code MongoIndexInitializer} and
 * {@code OpenSearchIndexInitializer}. A prompt discovered missing mid-request would mean answering
 * without instructions — the model would fall back on its own knowledge and the answer would look fine
 * while being ungrounded. That must be a boot failure, not a runtime surprise.
 */
@ApplicationScoped
public class PromptCatalog {

    private static final Logger LOG = Logger.getLogger(PromptCatalog.class.getName());

    static final String RESOURCE = "config/prompts.json";

    /**
     * Optional filesystem path replacing the bundled catalogue wholesale. The seam for user-supplied
     * prompts later; unset in {@code application.properties}, so {@code Optional<String>} per the
     * {@code ConfigText} convention.
     */
    @ConfigProperty(name = "app.prompts.path")
    Optional<String> overridePath;

    private Map<String, PromptTemplate> prompts = Map.of();
    private Map<String, TaskSpec> tasks = Map.of();

    /** Filled by {@link #readTasks}: each task's prose description, and which ones a digest may use. */
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Set<String> offerable = new LinkedHashSet<>();

    /**
     * The bundled catalogue with no override applied, loaded eagerly.
     *
     * <p>For tests and tooling with no CDI container. Deliberately loads the <em>real shipped file</em>
     * rather than offering a stub: a test asserting on prompt wording is only meaningful if it reads what
     * actually ships.
     */
    public static PromptCatalog bundled() {
        PromptCatalog catalog = new PromptCatalog();
        catalog.overridePath = Optional.empty();
        catalog.load();
        return catalog;
    }

    /**
     * Parse and validate at boot so a malformed catalogue stops the application rather than the first
     * search. Package-private and separately callable so tests exercise the same path without CDI.
     */
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

    /**
     * @throws NoSuchElementException if no prompt is filed under {@code id} — a caller asking for a
     *                                prompt that does not exist is a wiring bug, not a degraded mode
     */
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

    /** The prompt a task runs, resolved through the task's {@code prompt} reference. */
    public PromptTemplate promptForTask(String taskId) {
        return prompt(task(taskId).promptId());
    }

    public Set<String> promptIds() {
        return prompts.keySet();
    }

    public Set<String> taskIds() {
        return tasks.keySet();
    }

    /** Every bundled task, in file order. */
    public java.util.Collection<TaskSpec> tasks() {
        return tasks.values();
    }

    /** A task's display name, falling back to its id. Never null. */
    public String name(String taskId) {
        return names.getOrDefault(taskId, taskId);
    }

    /** A task's prose description, for whoever is choosing one. Never null. */
    public String description(String taskId) {
        return descriptions.getOrDefault(taskId, "");
    }

    /** Whether this bundled task is one a digest may be pointed at — see {@code "digest"} in the file. */
    public boolean offerableInDigest(String taskId) {
        return offerable.contains(taskId);
    }

    // ---- parsing -----------------------------------------------------------------------------

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
        // Unmodifiable rather than Map.copyOf: the latter is explicitly unordered, which would make
        // the catalogue's listing — and so the console's task list — vary between restarts.
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
            // A display name, so a picker shows "Score job postings" rather than the slug "job-fit".
            names.put(id, t.path("name").asText(id));
            // Not every task is one a user would attach to a digest: "answer" and "document-facets"
            // are machinery the read path runs for itself, and offering them would invite a digest
            // that quietly does nothing useful. Opt in per task rather than out.
            if (t.path("digest").asBoolean(false)) {
                offerable.add(id);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Read an optional enum-valued task field, case-insensitively. An unrecognised value is fatal rather
     * than silently defaulted: a typo would otherwise leave a task quietly running the wrong shape, and
     * this catalogue's whole contract is that a hand-edited file fails at boot instead of at request time.
     */
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

    // ---- validation --------------------------------------------------------------------------

    /**
     * Everything that can be checked without running a query is checked here, and every failure is fatal.
     * A hand-edited config file is the expected source of these mistakes, and each one degrades quietly
     * at runtime rather than erroring: a task pointing at a deleted prompt, a {@code {{typo}}} that would
     * reach the model verbatim, or a declared variable nothing supplies.
     */
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
                // The user template's own variables are supplied by the caller at render time, so only
                // the declared set is checked here; anything else must be declared to be reviewable.
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

    /**
     * Placeholders the calling code always supplies, so a prompt need not declare them. Kept small and
     * explicit: anything outside this set has to be declared, which is what makes a typo visible.
     */
    private static final Set<String> CALLER_SUPPLIED = Set.of("query", "sources");
}
