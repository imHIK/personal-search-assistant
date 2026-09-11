package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Task;
import java.util.List;

/**
 * Manages the user-written half of the task library. The bundled half is read-only and served straight
 * from the catalogue; everything here operates on {@link Task} records in Mongo.
 */
public interface TaskService {

    /** One row per task, bundled and user-written, in the order the console shows them. */
    List<LibraryEntry> list();

    /** @throws java.util.NoSuchElementException if no task is filed under {@code id} */
    LibraryEntry get(String id);

    /**
     * @throws IllegalArgumentException if the task is not valid — see {@link Task#problems()}
     */
    Task create(Task task);

    /**
     * @throws java.util.NoSuchElementException if there is no such user task
     * @throws IllegalStateException           if {@code id} names a bundled task, which is read-only
     * @throws IllegalArgumentException        if the result would not be valid
     */
    Task update(String id, Task patch);

    /**
     * An editable copy of any task, bundled or not. Not persisted — the console shows it in the editor
     * and the user saves it, so an abandoned duplicate leaves nothing behind.
     *
     * @throws java.util.NoSuchElementException if there is no such task
     */
    Task duplicate(String id);

    /**
     * @throws java.util.NoSuchElementException if there is no such user task
     * @throws IllegalStateException           if it is bundled, or a digest still refers to it
     */
    void delete(String id);

    /**
     * One task as the API presents it.
     *
     * @param builtIn        bundled tasks cannot be edited or deleted
     * @param usedBy         what in the application depends on this task, e.g. {@code "search"}; empty
     *                       for a task nothing but a digest points at
     * @param usableInDigest whether a digest may be pointed at it
     * @param task           the editable record for a user task; null for a bundled one
     */
    record LibraryEntry(String id, String name, String description, boolean builtIn,
                        boolean usableInDigest, List<String> usedBy, Task task) {

        public LibraryEntry {
            usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
        }
    }
}
