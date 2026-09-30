package io.personalassistant.api.dto;

import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.service.KnowledgeService;
import java.time.Instant;
import java.util.List;

/**
 * @param total entities matching the filter across all pages
 * @param limit the page size actually applied, after clamping
 */
public record EntityPageDto(List<Item> items, long total, int limit, int offset) {

    public record Item(
            String id,
            String iterableId,
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
            Instant createdAt,
            Instant updatedAt) {}

    public static EntityPageDto from(KnowledgeService.EntityPage page) {
        List<Item> items = page.items().stream().map(EntityPageDto::toItem).toList();
        return new EntityPageDto(items, page.total(), page.limit(), page.offset());
    }

    private static Item toItem(EntitySummary e) {
        var index = e.index();
        return new Item(
                e.id(),
                e.iterableId(),
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
                e.createdAt(),
                e.updatedAt());
    }
}
