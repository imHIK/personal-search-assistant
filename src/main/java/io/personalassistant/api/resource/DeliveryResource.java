package io.personalassistant.api.resource;

import io.personalassistant.api.dto.DeliveryDto;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.domain.service.PublishingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Read the publishing outbox, and put a dead-lettered delivery back in it. */
@Path("/api/deliveries")
@Produces(MediaType.APPLICATION_JSON)
public class DeliveryResource {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    @Inject
    PublishingService publishing;

    /**
     * Newest first. {@code ?channelId=}, {@code ?status=} and {@code ?refId=} (a digest run id) filter;
     * {@code ?limit=} is capped at 100.
     */
    @GET
    public List<DeliveryDto> list(@QueryParam("channelId") String channelId,
                                  @QueryParam("refId") String refId,
                                  @QueryParam("status") String status,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("offset") Integer offset) {
        int size = Math.min(limit == null ? DEFAULT_LIMIT : Math.max(limit, 1), MAX_LIMIT);
        return publishing.list(channelId, refId, parseStatus(status), size, offset == null ? 0 : offset)
                .stream().map(DeliveryDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public DeliveryDto get(@PathParam("id") String id) {
        return publishing.get(id).map(DeliveryDto::from)
                .orElseThrow(() -> ApiErrors.notFound("No delivery with id " + id));
    }

    /** {@code FAILED} → {@code PENDING} with attempts reset. 409 for any other status. */
    @POST
    @Path("/{id}/retry")
    public DeliveryDto retry(@PathParam("id") String id) {
        try {
            return DeliveryDto.from(publishing.retry(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }

    private static DeliveryStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest("Unknown delivery status: " + status);
        }
    }
}
