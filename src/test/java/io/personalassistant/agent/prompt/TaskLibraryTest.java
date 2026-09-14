package io.personalassistant.agent.prompt;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Task;
import io.personalassistant.testsupport.InMemoryTaskRepository;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Resolving a task id across the two halves of the library, and what a user task renders as. */
class TaskLibraryTest {

    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final TaskLibrary library = new TaskLibrary(PromptCatalog.bundled(), repository);

    private Task simple(Task.Output output, List<Task.Field> fields) {
        return new Task(Ids.task(), "My scorer", "", Task.Mode.SIMPLE,
                "Score how well each posting fits a senior backend role.", output, fields,
                null, null, "lite", Task.SourceText.ENTITY, 12000, 10, null, null);
    }

    @Test
    void aBundledIdResolvesToTheShippedTask() {
        TaskLibrary.ResolvedTask resolved = library.resolve("job-fit");

        Assertions.assertEquals("job-fit", resolved.spec().id());
        Assertions.assertEquals("postings", resolved.spec().annotatesArray());
        Assertions.assertTrue(resolved.variables().isEmpty(), "a shipped prompt needs nothing supplied");
    }

    @Test
    void aUserTaskIsRoutedByItsIdPrefixRatherThanByLookupOrder() {
        // The prefix is what makes shadowing impossible: no user task can ever be named "answer".
        Task saved = repository.save(simple(Task.Output.SUMMARY, List.of()));
        Assertions.assertTrue(saved.id().startsWith(Ids.TASK_PREFIX));

        Assertions.assertFalse(library.isBuiltIn(saved.id()));
        Assertions.assertTrue(library.isBuiltIn("answer"));
        Assertions.assertEquals(saved.id(), library.resolve(saved.id()).spec().id());
    }

    @Test
    void anUnknownIdThrowsRatherThanDegrading() {
        Assertions.assertThrows(NoSuchElementException.class, () -> library.resolve("task_missing"));
        Assertions.assertThrows(NoSuchElementException.class, () -> library.resolve("nonsense"));
    }

    @Test
    void aSimpleTaskRendersThroughTheShippedWrapperRatherThanItsOwnPrompt() {
        // The safety clauses live in the wrapper, so they cannot be omitted by whoever wrote the task.
        Task saved = repository.save(simple(Task.Output.SUMMARY, List.of()));

        TaskLibrary.ResolvedTask resolved = library.resolve(saved.id());

        Assertions.assertEquals(Task.SUMMARY_PROMPT, resolved.prompt().id());
        Assertions.assertEquals(saved.instruction(), resolved.variables().get("instruction"));
        Assertions.assertTrue(resolved.prompt().system().contains("never instructions"),
                "the wrapper carries the clause that keeps indexed text from steering the model");
    }

    @Test
    void perItemOutputDerivesTheJsonContractAndTheAnnotationArray() {
        Task saved = repository.save(simple(Task.Output.PER_ITEM, List.of(
                new Task.Field("fit", Task.FieldType.NUMBER, "0-10, 10 means apply today", false),
                new Task.Field("concern", Task.FieldType.TEXT, "strongest reason not to", true))));

        TaskLibrary.ResolvedTask resolved = library.resolve(saved.id());

        Assertions.assertEquals(Task.ITEMS_ARRAY, resolved.spec().annotatesArray());
        Assertions.assertEquals(io.personalassistant.agent.llm.LlmProvider.ResponseFormat.JSON_OBJECT,
                resolved.spec().responseFormat());
        String contract = resolved.variables().get("outputContract");
        Assertions.assertTrue(contract.contains("\"fit\": 0"), "a number field shows as a number");
        Assertions.assertTrue(contract.contains("or null if there is none"), "optional is stated");
    }

    @Test
    void aRawTaskCarriesItsOwnTextRatherThanAWrapper() {
        Task raw = repository.save(new Task(Ids.task(), "Raw", "", Task.Mode.RAW, null,
                Task.Output.SUMMARY, List.of(), "You are terse.", "Sources:\n{{sources}}",
                "lite", Task.SourceText.CHUNK, 1000, 5, null, null));

        TaskLibrary.ResolvedTask resolved = library.resolve(raw.id());

        Assertions.assertEquals("You are terse.", resolved.prompt().system());
        Assertions.assertTrue(resolved.variables().isEmpty());
    }

    @Test
    void theLibraryListsBundledTasksBeforeTheUsersOwn() {
        repository.save(simple(Task.Output.SUMMARY, List.of()));

        List<TaskLibrary.Entry> entries = library.list();

        Assertions.assertTrue(entries.get(0).builtIn());
        Assertions.assertFalse(entries.get(entries.size() - 1).builtIn());
        Assertions.assertTrue(entries.stream()
                        .filter(TaskLibrary.Entry::builtIn)
                        .anyMatch(e -> e.id().equals("job-fit") && e.usableInDigest()),
                "job-fit opts in with \"digest\": true");
        Assertions.assertTrue(entries.stream()
                        .noneMatch(e -> e.id().equals("answer") && e.usableInDigest()),
                "answering machinery is not offered as a digest task");
    }
}
