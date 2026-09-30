package io.personalassistant.api.resource;

import io.personalassistant.api.dto.OAuthStartDto;
import io.personalassistant.api.dto.OAuthStartResponseDto;
import io.personalassistant.domain.service.OAuthConnectService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

@Path("/api/connections/oauth/{provider}")
public class OAuthResource {

    private static final Logger LOG = Logger.getLogger(OAuthResource.class.getName());

    @Inject
    OAuthConnectService oauth;

    /**
     * The {@code Origin} header picks the console the user returns to (:8080 or the :5173 dev server); the
     * service checks it against the configured allow-list.
     */
    @POST
    @Path("/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public OAuthStartResponseDto start(@PathParam("provider") String provider, OAuthStartDto dto,
                                       @Context HttpHeaders headers) {
        try {
            String origin = headers.getHeaderString("Origin");
            return new OAuthStartResponseDto(oauth.start(dto.toRequest(provider, origin)));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Always redirects back to the console, success or failure: a person is looking at this, not a program.
     */
    @GET
    @Path("/callback")
    public Response callback(@PathParam("provider") String provider,
                             @QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null && !error.isBlank()) {
            return back(oauth.returnOriginFor(state), "error", error, null);
        }
        try {
            OAuthConnectService.Completed completed = oauth.complete(provider, state, code);
            return back(completed.returnTo(), "ok", null, completed.connection().id());
        } catch (RuntimeException e) {
            LOG.warning("OAuth callback for " + provider + " failed: " + e.getMessage());
            return back(oauth.returnOriginFor(state), "error", e.getMessage(), null);
        }
    }

    private static Response back(String origin, String outcome, String reason, String connectionId) {
        StringBuilder target = new StringBuilder(origin == null ? "" : origin)
                .append("/connections?oauth=").append(outcome);
        if (connectionId != null) {
            target.append("&id=").append(enc(connectionId));
        }
        if (reason != null && !reason.isBlank()) {
            target.append("&reason=").append(enc(reason));
        }
        return Response.seeOther(URI.create(target.toString())).build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
