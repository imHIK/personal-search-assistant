package io.personalassistant.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.api.dto.CursorDto;
import io.personalassistant.api.dto.EntityPageDto;
import io.personalassistant.api.dto.KnowledgeDto;
import io.personalassistant.api.dto.KnowledgePatchDto;
import io.personalassistant.domain.model.EntityQuery;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.service.KnowledgeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Path("/api/knowledge")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KnowledgeResource {

    @Inject
    KnowledgeService knowledgeService;

    @GET
    public List<Knowledge> list() {
        return knowledgeService.list();
    }

    @GET
    @Path("/{id}")
    public Knowledge get(@PathParam("id") String id) {
        return knowledgeService.get(id)
                .orElseThrow(() -> ApiErrors.notFound("No source with id " + id));
    }

    /**
     * A failed activation is still a 200, with {@code status: "ERROR"}; only a request rejected before
     * anything is persisted (an unparseable cron) is a 400.
     */
    @POST
    public Knowledge create(KnowledgeDto dto) {
        try {
            return knowledgeService.add(dto.toRequest());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** Changing the immutable {@code type} is a 400; editing a DELETED knowledge is a 409. */
    @PATCH
    @Path("/{id}")
    public Knowledge update(@PathParam("id") String id, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a patch body is required");
        }
        try {
            return knowledgeService.update(id, new KnowledgePatchDto(body).toPatch());
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiErrors.conflict(e.getMessage());
        }
    }

    /**
     * {@code status} takes a comma-separated list; absent means every status but DELETED. {@code iterableId}
     * repeats rather than taking a comma list, because a folder path may contain a comma. {@code limit} is
     * clamped to 1..200.
     */
    @GET
    @Path("/{id}/entities")
    public EntityPageDto entities(@PathParam("id") String id,
                                  @QueryParam("status") String status,
                                  @QueryParam("q") String q,
                                  @QueryParam("iterableId") List<String> iterableIds,
                                  @QueryParam("limit") @DefaultValue("50") int limit,
                                  @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            Set<String> groups = new LinkedHashSet<>();
            if (iterableIds != null) {
                iterableIds.stream().filter(v -> v != null && !v.isBlank()).forEach(groups::add);
            }
            EntityQuery query = new EntityQuery(parseStatuses(status), q, groups);
            return EntityPageDto.from(knowledgeService.listEntities(id, query, limit, offset));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    private static Set<EntityStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return Set.of();
        }
        Set<EntityStatus> parsed = new LinkedHashSet<>();
        for (String name : status.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(EntityStatus.valueOf(trimmed));
            }
        }
        return parsed;
    }

    @GET
    @Path("/{id}/cursors")
    public List<CursorDto> cursors(@PathParam("id") String id) {
        try {
            return knowledgeService.listCursors(id).stream().map(CursorDto::from).toList();
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/pause")
    public void pause(@PathParam("id") String id) {
        knowledgeService.pause(id);
    }

    @POST
    @Path("/{id}/resume")
    public void resume(@PathParam("id") String id) {
        knowledgeService.resume(id);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        knowledgeService.delete(id);
    }
}
