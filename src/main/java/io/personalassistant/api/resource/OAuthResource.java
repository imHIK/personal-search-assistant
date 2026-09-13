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

/**
 * The browser half of connecting an account: send the user to a provider's consent screen, then catch
 * them on the way back and store the credentials.
 *
 * <p>{@code {provider}} is resolved through the provider registry, so these two endpoints exist for
 * every installed {@code OAuthProvider} the moment its bean does — adding Slack or Notion adds no
 * route here.
 */
@Path("/api/connections/oauth/{provider}")
public class OAuthResource {

    private static final Logger LOG = Logger.getLogger(OAuthResource.class.getName());

    @Inject
    OAuthConnectService oauth;

    /**
     * Begin a consent flow. The {@code Origin} header decides which console the user is sent back to
     * (:8080 or the :5173 dev server); the service validates it against the configured allow-list.
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
        } catch (IllegalArgumentException e) { // unknown provider/type, or no OAuth client configured
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Where the provider sends the browser back to. Always answers with a redirect to the console —
     * a person is looking at this, not a program, so both success and failure have to land on a page
     * that can explain itself rather than on a JSON body.
     */
    @GET
    @Path("/callback")
    public Response callback(@PathParam("provider") String provider,
                             @QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null && !error.isBlank()) {
            // The user declined, or the provider refused before we were ever involved.
            return back(oauth.returnOriginFor(state), "error", error, null);
        }
        try {
            OAuthConnectService.Completed completed = oauth.complete(provider, state, code);
            return back(completed.returnTo(), "ok", null, completed.connection().id());
        } catch (RuntimeException e) {
            // Includes a rejected code and a failed re-verification. There is no useful status code to
            // return to a browser mid-redirect, so the reason travels in the query string instead.
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
