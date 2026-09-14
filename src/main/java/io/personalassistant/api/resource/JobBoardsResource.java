package io.personalassistant.api.resource;

import io.personalassistant.api.dto.CompanyLookupDto;
import io.personalassistant.ingestion.connector.ats.JobBoardsConnector;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Look up which job-board platform hosts a company, before committing it to a knowledge.
 *
 * <p>Read-only calls to public, unauthenticated board APIs — the same probes {@code discover()} makes.
 * Its purpose is the watchlist: reach here is a function of how many companies are named, and this
 * turns "is this company reachable?" from create-a-knowledge-and-see into one request for fifty names.
 */
@Path("/api/connectors/job-boards")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class JobBoardsResource {

    /**
     * Cap on names per request. Each one probes several platforms, so an unbounded list would be a
     * long-running request against APIs that are someone else's to pay for.
     */
    private static final int MAX_COMPANIES = 50;

    @Inject
    JobBoardsConnector connector;

    /**
     * Resolve each candidate name. A name that matches nothing comes back with {@code found: false}
     * rather than being omitted — knowing a company is unreachable is the point of asking.
     */
    @POST
    @Path("/lookup")
    public List<CompanyLookupDto> lookup(CompanyLookupDto.Request request) {
        List<String> companies = request == null || request.companies() == null
                ? List.of() : request.companies();
        if (companies.isEmpty()) {
            throw ApiErrors.badRequest("companies must not be empty");
        }
        if (companies.size() > MAX_COMPANIES) {
            throw ApiErrors.badRequest("at most " + MAX_COMPANIES
                    + " companies per request; got " + companies.size());
        }
        return connector.lookup(companies).stream().map(CompanyLookupDto::from).toList();
    }
}
