package io.personalassistant.api.resource;

import io.personalassistant.api.dto.KnowledgeDto;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.service.KnowledgeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

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
