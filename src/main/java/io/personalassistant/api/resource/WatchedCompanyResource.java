package io.personalassistant.api.resource;

import io.personalassistant.api.dto.WatchedCompanyDto;
import io.personalassistant.domain.service.WatchedCompanyService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.NoSuchElementException;

@Path("/api/watched-companies")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WatchedCompanyResource {

    @Inject
    WatchedCompanyService companies;

    @GET
    public List<WatchedCompanyDto> list() {
        return companies.list().stream().map(WatchedCompanyDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public WatchedCompanyDto get(@PathParam("id") String id) {
        return companies.get(id).map(WatchedCompanyDto::from)
                .orElseThrow(() -> ApiErrors.notFound("No watched company with id " + id));
    }

    /** 409 when the name is taken. */
    @POST
    public WatchedCompanyDto create(WatchedCompanyDto dto) {
        if (dto == null) {
            throw ApiErrors.badRequest("a body is required");
        }
        try {
            return WatchedCompanyDto.from(companies.create(dto.toFields()));
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }

    /** Adds the bundled companies not already watched; existing ones are untouched. */
    @POST
    @Path("/import-bundled")
    public WatchedCompanyDto.ImportResult importBundled() {
        return WatchedCompanyDto.ImportResult.from(companies.importBundled());
    }

    @PATCH
    @Path("/{id}")
    public WatchedCompanyDto update(@PathParam("id") String id, WatchedCompanyDto dto) {
        if (dto == null) {
            throw ApiErrors.badRequest("a body is required");
        }
        try {
            return WatchedCompanyDto.from(companies.update(id, dto.toFields()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }

    /** Stamps {@code lastCheckedAt} with the server's clock. */
    @POST
    @Path("/{id}/checked")
    public WatchedCompanyDto markChecked(@PathParam("id") String id) {
        try {
            return WatchedCompanyDto.from(companies.markChecked(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        try {
            companies.delete(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }
}
