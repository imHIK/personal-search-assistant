package io.personalassistant.agent.prompt;

import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Task;
import io.personalassistant.storage.repository.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Resolves a task id to something runnable, from either half of the library: the bundled catalogue in
 * {@code config/prompts.json}, or a user-written {@link Task} in Mongo.
 *
 * <p><strong>Why the two halves are layered rather than merged.</strong> {@link PromptCatalog} is
 * eager and fail-fast — a malformed built-in prompt stops the application, because answering with no
 * instructions is a failure that looks like success. User tasks cannot work that way: they are created
 * long after boot, so a bad one has to cost its own run and nothing else. Keeping them in separate
 * stores with separate validation timing is what lets both be true at once.
 *
 * <p>Routing is by id shape, not by lookup order. A user task's id always carries
 * {@link Ids#TASK_PREFIX}, and the bundled ids are slugs, so a user can never shadow {@code answer} and
 * a missing user task can never silently fall through to a built-in that happens to share its name.
 */
@ApplicationScoped
public class TaskLibrary {

    /**
     * A task ready to run: what to ask for, which prompt renders it, and the values that prompt needs
     * beyond the framework's own.
     *
     * @param variables values for the prompt's declared variables — empty for a bundled task, and for a
     *                  user-written one the instruction and reply contract that the shipped wrapper
     *                  renders around
     */
    public record ResolvedTask(TaskSpec spec, PromptTemplate prompt, Map<String, String> variables) {

        public ResolvedTask {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    private final PromptCatalog catalog;
    private final TaskRepository tasks;

    @Inject
    public TaskLibrary(PromptCatalog catalog, TaskRepository tasks) {
        this.catalog = catalog;
        this.tasks = tasks;
    }

    /**
     * @throws NoSuchElementException if no task is filed under {@code id} in either half. A caller
     *                                asking for a task that does not exist is a wiring bug for a
     *                                built-in and a dangling reference for a user task; neither has a
     *                                sensible degraded mode
     */
    public ResolvedTask resolve(String id) {
        if (id != null && id.startsWith(Ids.TASK_PREFIX)) {
            Task task = tasks.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("No task \"" + id + "\""));
            return resolve(task);
        }
        TaskSpec spec = catalog.task(id);
        return new ResolvedTask(spec, catalog.prompt(spec.promptId()), Map.of());
    }

    /** The runnable form of a user task, without a round trip — used by preview and by validation. */
    public ResolvedTask resolve(Task task) {
        TaskSpec spec = new TaskSpec(
                task.id(),
                task.promptId(),
                task.llmProfile(),
                task.contextChars(),
                task.maxSources(),
                task.sourceText() == Task.SourceText.ENTITY
                        ? TaskSpec.SourceText.ENTITY : TaskSpec.SourceText.CHUNK,
                task.perItem() ? LlmProvider.ResponseFormat.JSON_OBJECT
                        : LlmProvider.ResponseFormat.TEXT,
                task.perItem() ? Task.ITEMS_ARRAY : null);

        if (task.mode() == Task.Mode.RAW) {
            // Its own text, verbatim. Declaring no variables is correct rather than lax: the boot-time
            // declared-vs-used check exists to catch typos in a hand-edited file, and a user task is
            // instead checked when it is saved, where the author is present to read the error.
            return new ResolvedTask(spec,
                    new PromptTemplate(task.id(), task.description(), task.system(), task.user(),
                            List.of()),
                    Map.of());
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("instruction", task.instruction() == null ? "" : task.instruction());
        if (task.perItem()) {
            variables.put("outputContract", task.outputContract());
        }
        return new ResolvedTask(spec, catalog.prompt(task.promptId()), variables);
    }

    /** Every task in the library, bundled first, then the user's own newest-first. */
    public List<Entry> list() {
        List<Entry> out = new java.util.ArrayList<>();
        for (TaskSpec spec : catalog.tasks()) {
            out.add(Entry.builtIn(spec, catalog.name(spec.id()), catalog.description(spec.id()),
                    catalog.offerableInDigest(spec.id())));
        }
        for (Task task : tasks.findAll()) {
            out.add(Entry.user(task));
        }
        return out;
    }

    public Optional<Task> userTask(String id) {
        return id == null || !id.startsWith(Ids.TASK_PREFIX) ? Optional.empty() : tasks.findById(id);
    }

    /** Whether this id names a bundled task, which cannot be edited or deleted. */
    public boolean isBuiltIn(String id) {
        return id != null && !id.startsWith(Ids.TASK_PREFIX) && catalog.taskIds().contains(id);
    }

    /**
     * One row of the library as the API presents it.
     *
     * @param builtIn        bundled tasks are read-only: {@code answer} runs on every search answer and
     *                       {@code document-facets} on every search-by-document, so an edit to either
     *                       would degrade search silently. The console offers Duplicate instead
     * @param usableInDigest whether a digest may be pointed at it
     * @param task           the editable record, for a user task; null for a bundled one
     */
    public record Entry(String id, String name, String description, boolean builtIn,
                        boolean usableInDigest, Task task) {

        static Entry builtIn(TaskSpec spec, String name, String description,
                             boolean usableInDigest) {
            return new Entry(spec.id(), name, description, true, usableInDigest, null);
        }

        static Entry user(Task task) {
            return new Entry(task.id(), task.name(), task.description(), false, true, task);
        }
    }
}
