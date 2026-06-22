package io.personalassistant.api.resource;

import io.personalassistant.api.dto.SourceDto;
import io.personalassistant.storage.repository.SourceRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Manage connected sources. CRUD over the {@code sources} collection; the actual sync is
 * triggered via {@link IndexingResource}.
 */
@Path("/api/sources")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SourceResource {

    @Inject
    SourceRepository sources;

    @GET
    public List<?> list() {
        return sources.findAll();
    }

    @GET
    @Path("/{id}")
    public Object get(@PathParam("id") String id) {
        return sources.findById(id).orElseThrow(NotFoundException::new);
    }

    @POST
    public Object create(SourceDto dto) {
        // TODO: map DTO -> Source, validate via the matching connector, persist.
        throw new UnsupportedOperationException("wired in the next pass");
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        sources.delete(id);
    }
}
