package io.personalassistant.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.api.dto.EntityListDto;
import io.personalassistant.api.dto.EntityQueryDto;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.FacetValue;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.service.EntityService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Entities across knowledges, with filters on their stored fields. Not tied to any source type. */
@Path("/api/entities")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class EntitiesResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityService entities;

    /** A POST because the filters are a nested object no query string carries well. */
    @POST
    @Path("/query")
    public EntityListDto query(EntityQueryDto body) {
        EntityQueryDto request = body != null ? body
                : new EntityQueryDto(null, null, null, null, null, null, null);
        try {
            return EntityListDto.from(entities.query(request.toFilter(), request.limitOrDefault(),
                    request.offsetOrDefault()));
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** All list params are comma-separated. */
    @GET
    @Path("/facets")
    public Map<String, List<FacetValue>> facets(@QueryParam("entityTypes") String entityTypes,
                                                @QueryParam("knowledgeIds") String knowledgeIds,
                                                @QueryParam("fields") String fields,
                                                @QueryParam("limit") Integer limit) {
        try {
            Set<EntityType> types = new LinkedHashSet<>();
            split(entityTypes).forEach(t -> types.add(EntityType.valueOf(t.toUpperCase(Locale.ROOT))));
            EntityFilter scope = new EntityFilter(types, new LinkedHashSet<>(split(knowledgeIds)), null, null,
                    null);
            return entities.facets(scope, split(fields), limit == null ? 100 : limit);
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    /** A JSON object: each key is set, or removed when null. Leaves every other key alone. */
    @PATCH
    @Path("/{id}/custom")
    public EntityListDto.Item mergeCustom(@PathParam("id") String id, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiErrors.badRequest("a JSON object of keys to set or remove is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> values.put(entry.getKey(),
                entry.getValue().isNull() ? null : MAPPER.convertValue(entry.getValue(), Object.class)));
        try {
            return EntityListDto.Item.from(entities.mergeCustom(id, values));
        } catch (NoSuchElementException e) {
            throw ApiErrors.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
