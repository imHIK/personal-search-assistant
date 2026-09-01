package io.personalassistant.api.resource;

import io.personalassistant.api.dto.SearchRequestDto;
import io.personalassistant.api.dto.SearchResponseDto;
import io.personalassistant.domain.service.SearchService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Read path. {@code POST /api/search} with a {@link SearchRequestDto}.
 * The resource is a thin inbound adapter: map DTO -> domain, delegate, map back.
 */
@Path("/api/search")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject
    SearchService searchService;

    /**
     * Run a search. A blank query or an unknown {@code mode} is a {@code 400}; {@code topK} is clamped
     * by the service rather than rejected. An LLM failure while synthesizing an answer is <em>not</em>
     * an error status — the hits come back with {@code answerError} set.
     */
    @POST
    public SearchResponseDto search(SearchRequestDto request) {
        try {
            var response = searchService.search(request.toDomain());
            return SearchResponseDto.from(response);
        } catch (IllegalArgumentException e) {          // blank query / unknown mode
            throw new BadRequestException(e.getMessage());
        }
    }
}
