package io.personalassistant.api.resource;

import io.personalassistant.domain.service.IndexingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * Trigger and manage indexing. {@code POST /api/index/sources/{id}/sync} kicks off an
 * incremental sync. Synchronous now; becomes enqueue-and-return-202 once the async
 * pipeline lands — the route stays the same.
 */
@Path("/api/index")
@Produces(MediaType.APPLICATION_JSON)
public class IndexingResource {

    @Inject
    IndexingService indexing;

    @POST
    @Path("/sources/{id}/sync")
    public IndexingService.IndexRunResult sync(@PathParam("id") String sourceId) {
        return indexing.sync(sourceId);
    }

    @POST
    @Path("/documents/{id}/reindex")
    public void reindex(@PathParam("id") String documentId) {
        indexing.reindexDocument(documentId);
    }

    @DELETE
    @Path("/documents/{id}")
    public void delete(@PathParam("id") String documentId) {
        indexing.deleteDocument(documentId);
    }
}
