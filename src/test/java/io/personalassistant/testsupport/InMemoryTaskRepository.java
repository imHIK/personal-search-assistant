package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Task;
import io.personalassistant.storage.repository.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link TaskRepository} for tests, matching the other {@code InMemory*} fakes. */
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, Task> tasks = new LinkedHashMap<>();

    @Override
    public Task save(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<Task> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<Task> findAll() {
        List<Task> out = new ArrayList<>(tasks.values());
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public void delete(String id) {
        tasks.remove(id);
    }
}
