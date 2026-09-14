package io.personalassistant.api.resource;

import io.personalassistant.agent.llm.LlmProfiles;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The LLM profiles configured under {@code app.llm.profile.*}.
 *
 * <p>Exists so the task editor can offer the models actually configured rather than asking for a name
 * to be typed — a mistyped profile resolves to the provider defaults with only a log line to say so,
 * which is precisely the kind of quiet degradation a picker prevents.
 */
@Path("/api/llm-profiles")
@Produces(MediaType.APPLICATION_JSON)
public class LlmProfilesResource {

    @Inject
    LlmProfiles profiles;

    @GET
    public List<String> list() {
        return profiles.names();
    }
}
