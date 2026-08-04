package io.personalassistant.api.dto;

import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.service.KnowledgeService;
import java.time.Instant;
import java.util.List;

/**
 * Outbound REST payload for one page of a knowledge's entities.
 *
 * <p>{@code total} is the count matching the same filter, not the page length, so a caller can
 * render page controls without walking to the end.
 *
 * @param items  the page, newest-first
 * @param total  entities matching the filter across all pages
 * @param limit  the page size actually applied (the service clamps it)
 * @param offset the offset actually applied
 */
public record EntityPageDto(List<Item> items, long total, int limit, int offset) {

    /**
     * One row of the listing. {@code knowledgeId} is omitted — it is the path parameter — and
     * {@code IndexInfo} is flattened so a table binds straight to the fields.
     *
     * @param id             internal entity id
     * @param externalId     natural key within the source (path, message id…)
     * @param entityType     coarse classification (FILE / MESSAGE / …)
     * @param status         indexing lifecycle state
     * @param title          display title, or null
     * @param uri            citation locator, or null
     * @param checksum       content hash for change detection
     * @param chunkCount     chunks last written to the search index
     * @param embeddingModel model that produced those chunks' vectors
     * @param indexedAt      when the entity was last successfully indexed
     * @param error          last indexing error, or null
     * @param retryCount     indexing retry attempts so far
     * @param needsReindex   whether the entity is queued for re-indexing
     * @param updatedAt      last-modified timestamp — the listing sort key
     */
    public record Item(
            String id,
            String externalId,
            String entityType,
            String status,
            String title,
            String uri,
            String checksum,
            int chunkCount,
            String embeddingModel,
            Instant indexedAt,
            String error,
            int retryCount,
            boolean needsReindex,
            Instant updatedAt) {}

    public static EntityPageDto from(KnowledgeService.EntityPage page) {
        List<Item> items = page.items().stream().map(EntityPageDto::toItem).toList();
        return new EntityPageDto(items, page.total(), page.limit(), page.offset());
    }

    private static Item toItem(EntitySummary e) {
        var index = e.index();
        return new Item(
                e.id(),
                e.externalId(),
                e.entityType() == null ? null : e.entityType().name(),
                e.status() == null ? null : e.status().name(),
                e.title(),
                e.uri(),
                e.checksum(),
                index == null ? 0 : index.chunkCount(),
                index == null ? null : index.embeddingModel(),
                index == null ? null : index.indexedAt(),
                index == null ? null : index.error(),
                e.retryCount(),
                e.needsReindex(),
                e.updatedAt());
    }
}
