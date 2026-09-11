package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Task;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@code tasks} collection — the user-written half of the task library. The
 * bundled catalogue is not stored here; it is read from {@code config/prompts.json} at boot.
 */
public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(String id);

    /** Every user task, newest first. */
    List<Task> findAll();

    void delete(String id);
}
