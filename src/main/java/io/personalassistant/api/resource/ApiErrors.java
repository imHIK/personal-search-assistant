package io.personalassistant.api.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Failure responses that actually carry their reason.
 *
 * <p>The obvious spelling — {@code throw new BadRequestException("token revoked")} — is a trap on
 * RESTEasy Reactive: the message becomes the exception's {@code getMessage()} and the wire response is
 * {@code 400} with {@code content-length: 0}. Every reason a resource took the trouble to produce was
 * being dropped at the door, which is why the console could only ever say "something went wrong".
 *
 * <p>These build the status with a small JSON body instead ({@code {"message": ...}}). It is not an
 * exception mapper — resources still map exception to status themselves, per the architecture — just
 * the one place that knows how to attach the text so the mapping stays a one-liner at the call site.
 */
final class ApiErrors {

    private ApiErrors() {
    }

    static WebApplicationException badRequest(String message) {
        return of(Response.Status.BAD_REQUEST, message);
    }

    static WebApplicationException notFound(String message) {
        return of(Response.Status.NOT_FOUND, message);
    }

    static WebApplicationException conflict(String message) {
        return of(Response.Status.CONFLICT, message);
    }

    private static WebApplicationException of(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status)
                .entity(Map.of("message", message == null ? status.getReasonPhrase() : message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
