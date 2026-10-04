package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Task;
import java.util.List;

public interface TaskService {

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
     * Not persisted.
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
     * @param usedBy what depends on this task (e.g. {@code "search"}); empty when only digests point at it
     */
    record LibraryEntry(String id, String name, String description, boolean builtIn,
                        boolean usableInDigest, List<String> usedBy, Task task) {

        public LibraryEntry {
            usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
        }
    }
}
