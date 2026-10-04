package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Task;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(String id);

    /** Newest first. */
    List<Task> findAll();

    void delete(String id);
}
