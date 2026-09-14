package io.personalassistant.api.resource;

import io.personalassistant.api.dto.ChannelDto;
import io.personalassistant.api.dto.ChannelEditDto;
import io.personalassistant.api.dto.DeliveryDto;
import io.personalassistant.api.dto.PublishMessageDto;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.service.ChannelService;
import io.personalassistant.domain.service.PublishingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Manage publishing channels — the places messages can be sent — and queue messages to them.
 */
@Path("/api/channels")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChannelResource {

    @Inject
    ChannelService channels;

    @Inject
    PublishingService publishing;

    @GET
    public List<ChannelDto> list() {
        return channels.list().stream().map(ChannelDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public ChannelDto get(@PathParam("id") String id) {
        return channels.get(id).map(ChannelDto::from)
                .orElseThrow(() -> ApiErrors.notFound("No channel with id " + id));
    }

    @POST
    public ChannelDto create(ChannelDto dto) {
        try {
            return ChannelDto.from(channels.create(dto.toRequest()));
        } catch (IllegalArgumentException e) { // unknown type, no publisher, invalid target
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    @PATCH
    @Path("/{id}")
    public ChannelDto update(@PathParam("id") String id, ChannelEditDto dto) {
        try {
            return ChannelDto.from(channels.update(id, dto.toEdit()));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /**
     * Send a sample message now, bypassing the queue, and record the outcome. Returns 200 whether or not
     * the send worked — read {@code status} and {@code lastError}. A success also brings a channel parked
     * in {@code ERROR} back, releasing everything queued for it.
     */
    @POST
    @Path("/{id}/test")
    public ChannelDto test(@PathParam("id") String id) {
        try {
            return ChannelDto.from(channels.test(id));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    /**
     * Queue a message for this channel. 202: the delivery is written, not yet sent — poll
     * {@code GET /api/deliveries/{id}} for the outcome.
     */
    @POST
    @Path("/{id}/publish")
    public Response publish(@PathParam("id") String id, PublishMessageDto dto) {
        if (dto == null) {
            throw ApiErrors.badRequest("a message body is required");
        }
        try {
            Delivery delivery = publishing.enqueue(id, dto.toDomain(), Delivery.Origin.manual(), null);
            return Response.accepted(DeliveryDto.from(delivery)).build();
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** Delete a channel together with its queued and sent deliveries. 409 while a digest sends to it. */
    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        try {
            channels.delete(id);
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalStateException e) { // a digest still sends to it
            throw ApiErrors.conflict(e.getMessage());
        }
    }
}
