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

@Path("/api/connectors/job-boards")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class JobBoardsResource {

    /** Each name probes several platforms' public APIs. */
    private static final int MAX_COMPANIES = 50;

    @Inject
    JobBoardsConnector connector;

    /** A name that matches nothing comes back with {@code found: false} rather than being omitted. */
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
