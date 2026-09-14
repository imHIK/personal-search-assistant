package io.personalassistant.api.resource;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Reading one indexed item by id.
 *
 * <p>Exists for the places that hold an entity id and need to show a person something other than the
 * id — a digest that searches <em>by</em> a document has only {@code sourceEntityId}, and the console
 * was rendering "Like ent_3f9…" as the digest's description. Entities were otherwise reachable only
 * through their knowledge's paged listing, which is the wrong shape for resolving a single reference.
 *
 * <p>Deliberately thin: no content, no chunk information, no lease or retry state. This answers "what
 * is this id", and anything more would make it a second entity API alongside the listing.
 */
@Path("/api/entities")
@Produces(MediaType.APPLICATION_JSON)
public class EntityResource {

    @Inject
    EntityRepository entities;

    /** @param status the indexing lifecycle state, so a caller can say when an item is not searchable */
    public record EntitySummaryDto(String id, String knowledgeId, String title, String uri,
                                   String status) {}

    @GET
    @Path("/{id}")
    public EntitySummaryDto get(@PathParam("id") String id) {
        Entity entity = entities.findById(id)
                .orElseThrow(() -> ApiErrors.notFound("No item with id " + id));
        return new EntitySummaryDto(entity.id(), entity.knowledgeId(), entity.title(), entity.uri(),
                entity.status() == null ? null : entity.status().name());
    }
}
