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

/**
 * The task library: what a digest can be told to do with its results.
 *
 * <p>Two kinds of row come back. Bundled tasks ship in {@code config/prompts.json} and are read-only —
 * {@code answer} runs on every search answer and {@code document-facets} on every search-by-document,
 * so an edit to either would degrade search with nothing to show for it. Everything else is a user
 * task, created here and stored in Mongo.
 */
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

    /**
     * Create a task, or with {@code ?duplicateOf=} return an editable copy of an existing one
     * <em>without</em> saving it — the console shows it in the editor and the user saves it, so an
     * abandoned duplicate leaves nothing behind.
     */
    @POST
    public TaskDto create(@QueryParam("duplicateOf") String duplicateOf, TaskDto dto) {
        try {
            if (duplicateOf != null && !duplicateOf.isBlank()) {
                return TaskDto.from(tasks.duplicate(duplicateOf));
            }
            return TaskDto.from(tasks.create(dto.toDomain()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {          // invalid task, or a bad enum name
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    @PATCH
    @Path("/{id}")
    public TaskDto update(@PathParam("id") String id, JsonNode body) {
        // Taken as a tree rather than a bound record on purpose: which keys were *sent* is part of
        // this endpoint's contract, and binding loses it. TaskDto.patchOnto explains why.
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a patch body is required");
        }
        try {
            Task existing = requireEditable(id);
            tasks.update(id, TaskDto.patchOnto(existing, body));
            return TaskDto.from(tasks.get(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) {             // built in, so read-only
            throw ApiErrors.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * The stored task a patch is to be overlaid onto.
     *
     * <p>A bundled task has no stored row, so there is nothing to patch — the same {@code 409} the
     * service raises, raised before reading the body rather than after.
     */
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
