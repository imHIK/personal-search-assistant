package io.personalassistant.api.resource;

import io.personalassistant.api.dto.KnowledgeDto;
import io.personalassistant.api.dto.KnowledgePatchDto;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.service.KnowledgeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
        return knowledgeService.get(id).orElseThrow(NotFoundException::new);
    }

    @POST
    public Knowledge create(KnowledgeDto dto) {
        return knowledgeService.add(dto.toRequest());
    }

    /**
     * Partially edit a knowledge. Only present fields change (patch semantics); the service routes
     * config vs. provisioning edits internally. Attempting to change the immutable {@code type} is a
     * {@code 400}; editing a {@code DELETED} knowledge is a {@code 409}; an unknown id is a {@code 404}.
     */
    @PATCH
    @Path("/{id}")
    public Knowledge update(@PathParam("id") String id, KnowledgePatchDto dto) {
        try {
            return knowledgeService.update(id, dto.toPatch());
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        } catch (IllegalArgumentException e) {          // immutable type change / unknown type value
            throw new BadRequestException(e.getMessage());
        } catch (IllegalStateException e) {             // knowledge is DELETED
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
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
