package io.personalassistant.app;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.Task;
import io.personalassistant.domain.service.TaskService;
import io.personalassistant.testsupport.InMemoryDigestRepository;
import io.personalassistant.testsupport.InMemoryTaskRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The two rules that stop the task library breaking what depends on it — bundled tasks are read-only,
 * and a task a digest still points at cannot be deleted — plus save-time validation.
 */
class DefaultTaskServiceTest {

    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryDigestRepository digests = new InMemoryDigestRepository();
    private final TaskLibrary library = new TaskLibrary(PromptCatalog.bundled(), tasks);
    private final DefaultTaskService service = service();

    private DefaultTaskService service() {
        DefaultTaskService svc = new DefaultTaskService(tasks, library, digests);
        svc.answerTaskId = "answer";
        return svc;
    }

    private static Task simple(String instruction, List<Task.Field> fields) {
        return new Task(null, "My scorer", "Scores things", Task.Mode.SIMPLE, instruction,
                fields.isEmpty() ? Task.Output.SUMMARY : Task.Output.PER_ITEM, fields,
                null, null, "lite", Task.SourceText.ENTITY, 12000, 10, null, null);
    }

    @Test
    void aCreatedTaskGetsAGeneratedPrefixedId() {
        // Honouring a supplied id would put the no-shadowing guarantee in the caller's hands.
        Task created = service.create(simple("Score these.", List.of()).withId("answer"));

        Assertions.assertTrue(created.id().startsWith("task_"));
        Assertions.assertNotNull(created.createdAt());
    }

    @Test
    void aBundledTaskCannotBeEditedOrDeleted() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> service.update("answer", simple("Rewrite answering.", List.of())));
        Assertions.assertThrows(IllegalStateException.class, () -> service.delete("job-fit"));
    }

    @Test
    void aTaskADigestStillUsesCannotBeDeleted() {
        Task created = service.create(simple("Score these.", List.of()));
        digests.save(new Digest("dig_1", "New roles", "engineer", null, List.of(), Map.of(), "1d",
                SyncSchedule.ofInterval(Duration.ofDays(1)), created.id(), 10, false, null, true,
                true, null, null, null));

        IllegalStateException refused =
                Assertions.assertThrows(IllegalStateException.class, () -> service.delete(created.id()));

        Assertions.assertTrue(refused.getMessage().contains("New roles"),
                "the refusal names what to detach first");
    }

    @Test
    void aPlaceholderInAnInstructionIsRejectedWhenItIsSavedRatherThanWhenItRuns() {
        // The instruction is substituted into the wrapper verbatim, so a {{...}} either reaches the
        // model as a literal or collides with a framework variable — hours later, in a scheduled run.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.create(simple("Score against {{myProfile}}.", List.of())));
    }

    @Test
    void sourceIsReservedAsAFieldName() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.create(simple("Score these.", List.of(
                        new Task.Field("source", Task.FieldType.NUMBER, "", false)))));
    }

    @Test
    void aPerItemTaskNeedsAtLeastOneField() {
        Task noFields = new Task(null, "Empty", "", Task.Mode.SIMPLE, "Score these.",
                Task.Output.PER_ITEM, List.of(), null, null, "lite", Task.SourceText.CHUNK, 0, 0,
                null, null);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(noFields));
    }

    @Test
    void aRawTaskWithoutSourcesIsRejected() {
        // Without {{sources}} the model answers from its own knowledge and the run looks successful.
        Task raw = new Task(null, "Raw", "", Task.Mode.RAW, null, Task.Output.SUMMARY, List.of(),
                "You are terse.", "Tell me about jobs.", "lite", Task.SourceText.CHUNK, 0, 0,
                null, null);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(raw));
    }

    @Test
    void aRawTaskWithAnUnresolvablePlaceholderFailsAtSaveTime() {
        Task raw = new Task(null, "Raw", "", Task.Mode.RAW, null, Task.Output.SUMMARY, List.of(),
                "Today is {{whenever}}.", "Sources:\n{{sources}}", "lite", Task.SourceText.CHUNK,
                0, 0, null, null);

        IllegalArgumentException rejected =
                Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(raw));

        Assertions.assertTrue(rejected.getMessage().contains("whenever"));
    }

    @Test
    void updatingLeavesAbsentFieldsAlone() {
        Task created = service.create(simple("Score these.", List.of()));

        Task renamed = service.update(created.id(),
                new Task(null, "Renamed", null, null, null, null, null, null, null, null, null,
                        0, 0, null, null));

        Assertions.assertEquals("Renamed", renamed.name());
        Assertions.assertEquals("Score these.", renamed.instruction(), "untouched by the patch");
        Assertions.assertEquals(12000, renamed.contextChars());
        Assertions.assertEquals(created.createdAt(), renamed.createdAt());
    }

    @Test
    void duplicatingABundledTaskGivesItsRealPromptRatherThanAnApproximation() {
        Task copy = service.duplicate("job-fit");

        Assertions.assertEquals(Task.Mode.RAW, copy.mode());
        Assertions.assertTrue(copy.system().contains("fit"), "the shipped prompt, verbatim");
        Assertions.assertTrue(copy.name().endsWith("(copy)"));
        Assertions.assertTrue(tasks.findAll().isEmpty(), "a duplicate is not saved until the user saves it");
    }

    @Test
    void theLibrarySaysWhichTaskSearchDependsOn() {
        TaskService.LibraryEntry answer = service.get("answer");

        Assertions.assertTrue(answer.builtIn());
        Assertions.assertEquals(List.of("search"), answer.usedBy());
        Assertions.assertFalse(answer.usableInDigest());
    }

    @Test
    void anUnknownTaskIs404Shaped() {
        Assertions.assertThrows(NoSuchElementException.class, () -> service.get("task_nope"));
    }
}
