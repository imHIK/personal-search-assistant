package io.personalassistant.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.api.dto.TaskDto;
import io.personalassistant.domain.model.Task;
import io.personalassistant.domain.service.TaskService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.NoSuchElementException;

@Path("/api/tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TasksResource {

    @Inject
    TaskService tasks;

    @GET
    public List<TaskDto> list() {
        return tasks.list().stream().map(TaskDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public TaskDto get(@PathParam("id") String id) {
        try {
            return TaskDto.from(tasks.get(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    /** With {@code ?duplicateOf=}, returns an unsaved, editable copy of that task instead. */
    @POST
    public TaskDto create(@QueryParam("duplicateOf") String duplicateOf, TaskDto dto) {
        try {
            if (duplicateOf != null && !duplicateOf.isBlank()) {
                return TaskDto.from(tasks.duplicate(duplicateOf));
            }
            return TaskDto.from(tasks.create(dto.toDomain()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    @PATCH
    @Path("/{id}")
    public TaskDto update(@PathParam("id") String id, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a patch body is required");
        }
        try {
            Task existing = requireEditable(id);
            tasks.update(id, TaskDto.patchOnto(existing, body));
            return TaskDto.from(tasks.get(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    private Task requireEditable(String id) {
        TaskService.LibraryEntry entry = tasks.get(id);
        if (entry.builtIn() || entry.task() == null) {
            throw new IllegalStateException("Task \"" + id + "\" is built in and cannot be edited");
        }
        return entry.task();
    }

    /** {@code 409} when the task is built in, or when a digest still points at it. */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            tasks.delete(id);
            return Response.noContent().build();
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }
}
