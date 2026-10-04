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
 * Whether a re-index re-fetches from the source is the connector's call (and
 * {@code app.indexing.refetch-on-reindex}), never the caller's.
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
     * Revives FAILED cursors and entities with a fresh retry budget, the only way out of FAILED. Separate
     * from {@code /sync}, which only re-arms forward cursors.
     */
    @POST
    @Path("/knowledge/{id}/retry-failed")
    public IndexingService.RetryTrigger retryFailed(@PathParam("id") String knowledgeId) {
        return indexing.retryFailed(knowledgeId);
    }

    @POST
    @Path("/knowledge/{id}/reindex")
    public IndexingService.ReindexTrigger reindexKnowledge(@PathParam("id") String knowledgeId) {
        return indexing.reindexKnowledge(knowledgeId);
    }

    /**
     * When the connector stages a copy, the re-fetch is synchronous: this can take as long as one download.
     */
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
