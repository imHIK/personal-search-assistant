package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.service.EntityService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** @param total entities matching the filter across all pages */
public record EntityListDto(List<Item> items, long total, int limit, int offset) {

    /**
     * @param enriched what the knowledge's metadata task produced; empty without one
     * @param custom the user's marks, e.g. {@code applied} / {@code hidden}
     */
    public record Item(
            String id,
            String knowledgeId,
            String iterableId,
            String externalId,
            String entityType,
            String status,
            String title,
            String uri,
            Map<String, Object> metadata,
            Map<String, Object> enriched,
            Map<String, Object> custom,
            String enrichmentError,
            Instant createdAt,
            Instant updatedAt) {

        public static Item from(Entity e) {
            return new Item(e.id(), e.knowledgeId(), e.iterableId(), e.externalId(),
                    e.entityType() == null ? null : e.entityType().name(),
                    e.status() == null ? null : e.status().name(),
                    e.title(), e.uri(), e.metadata(), e.enriched(), e.custom(),
                    e.enrichment() == null ? null : e.enrichment().error(),
                    e.createdAt(), e.updatedAt());
        }
    }

    public static EntityListDto from(EntityService.Page page) {
        return new EntityListDto(page.items().stream().map(Item::from).toList(), page.total(), page.limit(),
                page.offset());
    }
}
