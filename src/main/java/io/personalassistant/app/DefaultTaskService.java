package io.personalassistant.app;

import io.personalassistant.agent.prompt.PromptTemplate;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.Task;
import io.personalassistant.domain.service.TaskService;
import io.personalassistant.storage.repository.DigestRepository;
import io.personalassistant.storage.repository.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DefaultTaskService implements TaskService {

    private final TaskRepository tasks;
    private final TaskLibrary library;
    private final DigestRepository digests;

    @ConfigProperty(name = "app.agent.task", defaultValue = "answer")
    String answerTaskId;

    @Inject
    public DefaultTaskService(TaskRepository tasks, TaskLibrary library, DigestRepository digests) {
        this.tasks = tasks;
        this.library = library;
        this.digests = digests;
    }

    @Override
    public List<LibraryEntry> list() {
        List<LibraryEntry> out = new ArrayList<>();
        for (TaskLibrary.Entry entry : library.list()) {
            out.add(new LibraryEntry(entry.id(), entry.name(), entry.description(), entry.builtIn(),
                    entry.usableInDigest(), usedBy(entry.id()), entry.task()));
        }
        return out;
    }

    @Override
    public LibraryEntry get(String id) {
        return list().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No task \"" + id + "\""));
    }

    @Override
    public Task create(Task task) {
        Instant now = Instant.now();
        // The id is generated, never accepted: the task_ prefix is what stops a user task shadowing a bundled
        // one.
        Task stored = validated(task.withId(Ids.task()).withTimestamps(now, now));
        return tasks.save(stored);
    }

    @Override
    public Task update(String id, Task patch) {
        Task existing = requireUserTask(id);
        Task merged = new Task(
                existing.id(),
                patch.name() == null || patch.name().isBlank() ? existing.name() : patch.name(),
                patch.description() == null ? existing.description() : patch.description(),
                patch.mode() == null ? existing.mode() : patch.mode(),
                patch.instruction() == null ? existing.instruction() : patch.instruction(),
                patch.output() == null ? existing.output() : patch.output(),
                patch.fields() == null || patch.fields().isEmpty() ? existing.fields() : patch.fields(),
                patch.system() == null ? existing.system() : patch.system(),
                patch.user() == null ? existing.user() : patch.user(),
                patch.llmProfile() == null ? existing.llmProfile() : patch.llmProfile(),
                patch.sourceText() == null ? existing.sourceText() : patch.sourceText(),
                patch.contextChars() <= 0 ? existing.contextChars() : patch.contextChars(),
                patch.maxSources() <= 0 ? existing.maxSources() : patch.maxSources(),
                existing.createdAt(),
                Instant.now());
        return tasks.save(validated(merged));
    }

    @Override
    public Task duplicate(String id) {
        LibraryEntry entry = get(id);
        if (!entry.builtIn()) {
            Task source = entry.task();
            return new Task(null, copyName(source.name()), source.description(), source.mode(),
                    source.instruction(), source.output(), source.fields(), source.system(),
                    source.user(), source.llmProfile(), source.sourceText(), source.contextChars(),
                    source.maxSources(), null, null);
        }
        // A bundled task is copied as it really is, prompt text in RAW mode; an approximation would be
        // something that never ran.
        TaskLibrary.ResolvedTask resolved = library.resolve(id);
        PromptTemplate prompt = resolved.prompt();
        return new Task(null, copyName(entry.name()), entry.description(), Task.Mode.RAW,
                null, Task.Output.SUMMARY, List.of(), prompt.system(), prompt.user(),
                resolved.spec().llmProfile(),
                resolved.spec().sourceText() == io.personalassistant.agent.prompt.TaskSpec
                        .SourceText.ENTITY ? Task.SourceText.ENTITY : Task.SourceText.CHUNK,
                resolved.spec().contextChars(), resolved.spec().maxSources(), null, null);
    }

    @Override
    public void delete(String id) {
        requireUserTask(id);
        List<String> used = digestsUsing(id);
        if (!used.isEmpty()) {
            // Refused, naming the digests: deleting it would fail their every run with "no such task".
            throw new IllegalStateException(
                    "This task is still used by: " + String.join(", ", used));
        }
        tasks.delete(id);
    }

    private Task validated(Task task) {
        List<String> problems = task.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        if (task.mode() == Task.Mode.RAW) {
            dryRender(task);
        }
        return task;
    }

    /**
     * Renders the prompt once so a placeholder typo is a 400 while its author is present, not a failed
     * scheduled run hours later.
     */
    private void dryRender(Task task) {
        PromptTemplate prompt = library.resolve(task).prompt();
        Map<String, String> probe = Map.of("today", "2026-01-01", "fence", "\"\"\"",
                "truncationMarker", "[…truncated]", "query", "probe", "sources", "[1] probe");
        try {
            prompt.renderSystem(probe);
            prompt.renderUser(probe);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private Task requireUserTask(String id) {
        if (library.isBuiltIn(id)) {
            throw new IllegalStateException("\"" + id + "\" is built in and cannot be changed. "
                    + "Duplicate it to make an editable copy.");
        }
        return library.userTask(id)
                .orElseThrow(() -> new NoSuchElementException("No task \"" + id + "\""));
    }

    private List<String> usedBy(String id) {
        return id.equals(answerTaskId) ? List.of("search") : List.of();
    }

    private List<String> digestsUsing(String taskId) {
        List<String> names = new ArrayList<>();
        for (Digest digest : digests.findAll()) {
            if (taskId.equals(digest.taskId())) {
                names.add(digest.name());
            }
        }
        return names;
    }

    private static String copyName(String name) {
        return (name == null || name.isBlank() ? "Task" : name) + " (copy)";
    }
}
