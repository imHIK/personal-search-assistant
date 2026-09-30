package io.personalassistant.api.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Error responses that carry their reason. On RESTEasy Reactive, {@code throw new BadRequestException("…")}
 * sends a 400 with an empty body; these attach {@code {"message": …}}.
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
