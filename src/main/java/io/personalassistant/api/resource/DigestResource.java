package io.personalassistant.api.resource;

import io.personalassistant.api.dto.DigestDto;
import io.personalassistant.api.dto.DigestRunDto;
import io.personalassistant.domain.service.DigestService;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Scheduled saved searches: a query (or a source document), a cadence, a look-back window, and an
 * optional prompt-catalogue task over the results. Each execution is kept as a run, which is both the
 * history and how "only what is new" is computed.
 */
@Path("/api/digests")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DigestResource {

    /** Cap on how many runs one listing returns; the history is unbounded. */
    private static final int MAX_RUNS = 100;

    @Inject
    DigestService digests;

    @GET
    public List<DigestDto> list() {
        return digests.list().stream().map(DigestDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public DigestDto get(@PathParam("id") String id) {
        return digests.get(id).map(DigestDto::from)
                .orElseThrow(() -> ApiErrors.notFound("No digest with id " + id));
    }

    @POST
    public DigestDto create(DigestDto dto) {
        try {
            return DigestDto.from(digests.create(dto.toDomain()));
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** Pause or resume scheduling. A paused digest can still be run by hand. */
    @PATCH
    @Path("/{id}")
    public DigestDto setEnabled(@PathParam("id") String id, EnabledDto dto) {
        if (dto == null || dto.enabled() == null) {
            throw ApiErrors.badRequest("enabled must be true or false");
        }
        try {
            return DigestDto.from(digests.setEnabled(id, dto.enabled()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        digests.delete(id);
        return Response.noContent().build();
    }

    /**
     * Run now, without waiting for the schedule, and return the run. A search or task failure comes
     * back as a run carrying {@code error} rather than as a 5xx — the run happened, it just failed.
     */
    @POST
    @Path("/{id}/run")
    public DigestRunDto runNow(@PathParam("id") String id) {
        try {
            return DigestRunDto.from(digests.run(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @GET
    @Path("/{id}/runs")
    public List<DigestRunDto> runs(@PathParam("id") String id, @QueryParam("limit") Integer limit) {
        int capped = Math.clamp(limit == null ? 20 : limit, 1, MAX_RUNS);
        return digests.runs(id, capped).stream().map(DigestRunDto::from).toList();
    }

    @GET
    @Path("/{id}/runs/latest")
    public DigestRunDto latest(@PathParam("id") String id) {
        return digests.latestRun(id).map(DigestRunDto::from)
                .orElseThrow(() -> ApiErrors.notFound("Digest " + id + " has no run yet"));
    }

    /** Body of the enable/disable patch. */
    public record EnabledDto(Boolean enabled) {}
}
