package io.personalassistant.api.resource;

import io.personalassistant.api.dto.SearchRequestDto;
import io.personalassistant.api.dto.SearchResponseDto;
import io.personalassistant.domain.service.SearchService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/search")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject
    SearchService searchService;

    /**
     * A blank query or an unknown {@code mode} is a 400; {@code topK} is clamped, not rejected. An LLM
     * failure is not an error status: the hits come back with {@code answerError} set.
     */
    @POST
    public SearchResponseDto search(SearchRequestDto request) {
        try {
            var response = searchService.search(request.toDomain());
            return SearchResponseDto.from(response);
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest(e.getMessage());
        }
    }
}
