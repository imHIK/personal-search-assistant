package io.personalassistant.api.resource;

import io.personalassistant.agent.llm.LlmProfiles;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/llm-profiles")
@Produces(MediaType.APPLICATION_JSON)
public class LlmProfilesResource {

    @Inject
    LlmProfiles profiles;

    /** Profiles LLM connections serve, plus those the bundled tasks ask for. */
    @GET
    public List<String> list() {
        return profiles.names();
    }
}
