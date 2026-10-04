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
 * Resolves a task id from the bundled catalogue or a user-written Task. Routing is by id shape: user task ids
 * carry Ids.TASK_PREFIX, so a user task can never shadow a built-in.
 */
@ApplicationScoped
public class TaskLibrary {

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

    /** @throws NoSuchElementException if no task is filed under {@code id} in either half */
    public ResolvedTask resolve(String id) {
        if (id != null && id.startsWith(Ids.TASK_PREFIX)) {
            Task task = tasks.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("No task \"" + id + "\""));
            return resolve(task);
        }
        TaskSpec spec = catalog.task(id);
        return new ResolvedTask(spec, catalog.prompt(spec.promptId()), Map.of());
    }

    public ResolvedTask resolve(Task task) {
        TaskSpec spec = new TaskSpec(
                task.id(),
                task.promptId(),
                task.llmProfile(),
                task.contextChars(),
                task.maxSources(),
                task.sourceText() == Task.SourceText.ENTITY
                        ? TaskSpec.SourceText.ENTITY : TaskSpec.SourceText.CHUNK,
                task.perItem() || task.metadata() ? LlmProvider.ResponseFormat.JSON_OBJECT
                        : LlmProvider.ResponseFormat.TEXT,
                task.perItem() ? Task.ITEMS_ARRAY : null);

        if (task.mode() == Task.Mode.RAW) {
            return new ResolvedTask(spec,
                    new PromptTemplate(task.id(), task.description(), task.system(), task.user(),
                            List.of()),
                    Map.of());
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("instruction", task.instruction() == null ? "" : task.instruction());
        if (task.perItem() || task.metadata()) {
            variables.put("outputContract", task.outputContract());
        }
        return new ResolvedTask(spec, catalog.prompt(task.promptId()), variables);
    }

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

    public boolean isBuiltIn(String id) {
        return id != null && !id.startsWith(Ids.TASK_PREFIX) && catalog.taskIds().contains(id);
    }

    public record Entry(String id, String name, String description, boolean builtIn,
                        boolean usableInDigest, Task task) {

        static Entry builtIn(TaskSpec spec, String name, String description,
                             boolean usableInDigest) {
            return new Entry(spec.id(), name, description, true, usableInDigest, null);
        }

        /** A metadata task describes one entity, not a batch of results, so a digest cannot run it. */
        static Entry user(Task task) {
            return new Entry(task.id(), task.name(), task.description(), false, !task.metadata(), task);
        }
    }
}
