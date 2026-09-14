package io.personalassistant.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.api.dto.DigestDto;
import io.personalassistant.api.dto.DigestPatchDto;
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

    /**
     * Edit a digest. Any field may be sent; anything absent is left alone, so the pause/resume body
     * {@code {"enabled": false}} still works exactly as before.
     *
     * <p>The run history survives an edit, which matters more than it looks: the history is the
     * already-seen set, so the previous delete-and-recreate route silently made an edited digest
     * re-report its whole window. Use {@code POST /{id}/reset-history} when that is what you want.
     */
    @PATCH
    @Path("/{id}")
    public DigestDto update(@PathParam("id") String id, JsonNode body) {
        // Taken as a tree rather than a bound record on purpose: which keys were *sent* is part of
        // this endpoint's contract, and binding loses it. DigestPatchDto explains why.
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a patch body is required");
        }
        try {
            return DigestDto.from(digests.update(id, new DigestPatchDto(body).toPatch()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {          // unparseable schedule/window, or empty query
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Forget what has already been reported, keeping the recorded runs. The next run may repeat things
     * already seen — the point of the operation after widening a query.
     */
    @POST
    @Path("/{id}/reset-history")
    public DigestDto resetHistory(@PathParam("id") String id) {
        try {
            return DigestDto.from(digests.resetHistory(id));
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
    public List<DigestRunDto> runs(@PathParam("id") String id, @QueryParam("limit") Integer limit,
                                   @QueryParam("offset") Integer offset) {
        int capped = Math.clamp(limit == null ? 20 : limit, 1, MAX_RUNS);
        return digests.runs(id, capped, offset == null ? 0 : Math.max(offset, 0)).stream()
                .map(DigestRunDto::from).toList();
    }

    @GET
    @Path("/{id}/runs/latest")
    public DigestRunDto latest(@PathParam("id") String id) {
        return digests.latestRun(id).map(DigestRunDto::from)
                .orElseThrow(() -> ApiErrors.notFound("Digest " + id + " has no run yet"));
    }

    /** One run by id. {@code 404} when it is not this digest's, so a stale link cannot leak a run. */
    @GET
    @Path("/{id}/runs/{runId}")
    public DigestRunDto run(@PathParam("id") String id, @PathParam("runId") String runId) {
        return digests.run(id, runId).map(DigestRunDto::from)
                .orElseThrow(() -> ApiErrors.notFound("No run " + runId + " for digest " + id));
    }
}
