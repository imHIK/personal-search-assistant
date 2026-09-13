package io.personalassistant.api.resource;

import io.personalassistant.api.dto.ConnectionDto;
import io.personalassistant.api.dto.ConnectionEditDto;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.service.ConnectionService;
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
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Manage reusable, per-account connections. A connection holds the credentials a knowledge
 * authenticates through, so a user can register several accounts of the same connector (e.g. two
 * Gmail logins) and bind different knowledges to whichever they want; one connection per type is the
 * default used when a knowledge names none.
 */
@Path("/api/connections")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ConnectionResource {

    @Inject
    ConnectionService connectionService;

    @GET
    public List<Connection> list(@QueryParam("type") String type) {
        if (type == null || type.isBlank()) {
            return connectionService.list();
        }
        return connectionService.listByType(type.trim());
    }

    @GET
    @Path("/{id}")
    public Connection get(@PathParam("id") String id) {
        return connectionService.get(id)
                .orElseThrow(() -> ApiErrors.notFound("No connection with id " + id));
    }

    /** Create a connection: verifies the credentials, then persists and assigns the type default. */
    @POST
    public Connection create(ConnectionDto dto) {
        try {
            return connectionService.create(dto.toRequest());
        } catch (IllegalArgumentException e) { // unknown type, no-connection connector, or bad creds
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    @PATCH
    @Path("/{id}")
    public Connection update(@PathParam("id") String id, ConnectionEditDto dto) {
        try {
            return connectionService.update(id, dto.toEdit());
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) { // re-verification failed
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Re-check the stored credentials and record the outcome. Returns 200 with the refreshed
     * connection whether or not the check passed — read {@code status} and {@code lastError}. Bad
     * credentials are a result to display, not a 4xx.
     */
    @POST
    @Path("/{id}/test")
    public Connection test(@PathParam("id") String id) {
        try {
            return connectionService.test(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    /** Make this connection the default for its type. */
    @POST
    @Path("/{id}/default")
    public Connection setDefault(@PathParam("id") String id) {
        try {
            return connectionService.setDefault(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        try {
            connectionService.delete(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) { // still bound to knowledges
            throw ApiErrors.conflict(e.getMessage());
        }
    }
}
