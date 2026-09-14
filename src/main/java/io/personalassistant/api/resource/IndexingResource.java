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
 * actions: kick a forward sync for a knowledge, force re-indexing of one entity or a whole
 * knowledge, or remove an entity (its chunks are deleted by the indexing stage).
 *
 * <p>Re-index means "make this current again" and nothing finer. Whether that requires going back to
 * the source for the content is decided by the connector and {@code app.indexing.refetch-on-reindex},
 * never by the caller — the distinction is about where we happened to put the bytes, which is not
 * something a caller should have to know.
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
     * retry budget, and release any cursor still held by a rate limit. Separate from {@code /sync}
     * because that one only re-arms forward cursors and a dead-letter may be either direction; see
     * {@link IndexingService#retryFailed}.
     */
    @POST
    @Path("/knowledge/{id}/retry-failed")
    public IndexingService.RetryTrigger retryFailed(@PathParam("id") String knowledgeId) {
        return indexing.retryFailed(knowledgeId);
    }

    /**
     * Re-index every entity of a knowledge. Content is re-fetched first for connectors whose stored
     * reference is a staged copy, which shows up in the response as a non-zero {@code refetching} and
     * a set of rewound cursors — the work itself is done by the ingestion and indexing jobs.
     */
    @POST
    @Path("/knowledge/{id}/reindex")
    public IndexingService.ReindexTrigger reindexKnowledge(@PathParam("id") String knowledgeId) {
        return indexing.reindexKnowledge(knowledgeId);
    }

    /**
     * Re-index one entity, re-fetching its content first when the connector stages a copy rather than
     * referencing the source file. That fetch is synchronous, so this can take as long as one
     * download; everything after it is the ordinary indexing queue.
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
