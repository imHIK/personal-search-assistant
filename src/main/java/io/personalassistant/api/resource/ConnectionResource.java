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

    /** Verifies the credentials before persisting. */
    @POST
    public Connection create(ConnectionDto dto) {
        try {
            return connectionService.create(dto.toRequest());
        } catch (IllegalArgumentException e) {
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
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** 200 whether or not the check passed: read {@code status} and {@code lastError}. */
    @POST
    @Path("/{id}/test")
    public Connection test(@PathParam("id") String id) {
        try {
            return connectionService.test(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

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
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }
}
