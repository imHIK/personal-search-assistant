package io.personalassistant.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.api.dto.CursorDto;
import io.personalassistant.api.dto.EntityPageDto;
import io.personalassistant.api.dto.KnowledgeDto;
import io.personalassistant.api.dto.KnowledgePatchDto;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.service.KnowledgeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Manage connected knowledge sources. {@code POST /api/knowledge} validates the connector,
 * discovers iterables, creates cursors and activates the knowledge; the ingestion/indexing jobs
 * then keep it in sync.
 */
@Path("/api/knowledge")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KnowledgeResource {

    @Inject
    KnowledgeService knowledgeService;

    @GET
    public List<Knowledge> list() {
        return knowledgeService.list();
    }

    @GET
    @Path("/{id}")
    public Knowledge get(@PathParam("id") String id) {
        return knowledgeService.get(id)
                .orElseThrow(() -> ApiErrors.notFound("No source with id " + id));
    }

    /**
     * Create and activate a knowledge. An activation failure is still a {@code 200} with
     * {@code status: "ERROR"}; only a request the service rejects before persisting anything (an
     * unparseable cron) is a {@code 400}.
     */
    @POST
    public Knowledge create(KnowledgeDto dto) {
        try {
            return knowledgeService.add(dto.toRequest());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Partially edit a knowledge. Only present fields change (patch semantics); the service routes
     * config vs. provisioning edits internally. Attempting to change the immutable {@code type} is a
     * {@code 400}; editing a {@code DELETED} knowledge is a {@code 409}; an unknown id is a {@code 404}.
     */
    @PATCH
    @Path("/{id}")
    public Knowledge update(@PathParam("id") String id, JsonNode body) {
        // Taken as a tree rather than a bound record on purpose: which keys were *sent* is part of
        // this endpoint's contract, and binding loses it. KnowledgePatchDto explains why.
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a patch body is required");
        }
        try {
            return knowledgeService.update(id, new KnowledgePatchDto(body).toPatch());
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {          // immutable type change / unknown type value
            throw ApiErrors.badRequest(e.getMessage());
        } catch (IllegalStateException e) {             // knowledge is DELETED
            throw ApiErrors.conflict(e.getMessage());
        }
    }

    /**
     * Page this knowledge's entities, newest-first. {@code status} filters by {@code EntityStatus}
     * name (blank/absent = all); {@code limit} is clamped to {@code 1..200} by the service. An
     * unknown status name or a negative offset is a {@code 400}; an unknown id is a {@code 404}.
     */
    @GET
    @Path("/{id}/entities")
    public EntityPageDto entities(@PathParam("id") String id,
                                  @QueryParam("status") String status,
                                  @QueryParam("limit") @DefaultValue("50") int limit,
                                  @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            EntityStatus filter = status == null || status.isBlank() ? null : EntityStatus.valueOf(status);
            return EntityPageDto.from(knowledgeService.listEntities(id, filter, limit, offset));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {          // unknown status name / negative offset
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** This knowledge's ingestion cursors — the real sync-progress view. {@code 404} on unknown id. */
    @GET
    @Path("/{id}/cursors")
    public List<CursorDto> cursors(@PathParam("id") String id) {
        try {
            return knowledgeService.listCursors(id).stream().map(CursorDto::from).toList();
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/pause")
    public void pause(@PathParam("id") String id) {
        knowledgeService.pause(id);
    }

    @POST
    @Path("/{id}/resume")
    public void resume(@PathParam("id") String id) {
        knowledgeService.resume(id);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        knowledgeService.delete(id);
    }
}
