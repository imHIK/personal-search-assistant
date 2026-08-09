package io.personalassistant.api.resource;

import io.personalassistant.domain.service.IndexingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Trigger and manage indexing. The pipeline runs continuously; these endpoints expose the manual
 * actions: kick a forward sync for a knowledge, force re-indexing of a single entity (no
 * re-fetch), or remove an entity (its chunks are deleted by the indexing stage).
 */
@Path("/api/index")
@Produces(MediaType.APPLICATION_JSON)
public class IndexingResource {

    @Inject
    IndexingService indexing;

    @POST
    @Path("/knowledge/{id}/sync")
    public IndexingService.SyncTrigger sync(@PathParam("id") String knowledgeId) {
        return indexing.triggerSync(knowledgeId);
    }

    /**
     * Revive a knowledge's dead-lettered work — {@code FAILED} cursors and entities — with a fresh
     * retry budget. Separate from {@code /sync} because that one only re-arms forward cursors and a
     * dead-letter may be either direction; see {@link IndexingService#retryFailed}.
     */
    @POST
    @Path("/knowledge/{id}/retry-failed")
    public IndexingService.RetryTrigger retryFailed(@PathParam("id") String knowledgeId) {
        return indexing.retryFailed(knowledgeId);
    }

    @POST
    @Path("/entities/{id}/reindex")
    public void reindex(@PathParam("id") String entityId) {
        indexing.reindexEntity(entityId);
    }

    @DELETE
    @Path("/entities/{id}")
    public void delete(@PathParam("id") String entityId) {
        indexing.deleteEntity(entityId);
    }
}
