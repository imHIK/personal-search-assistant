package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;

/**
 * Not an Entity: raw and content.text are the bulk of one, and a listing needs neither.
 *
 * @param createdAt never moves: the honest answer to when this arrived
 * @param updatedAt last write from either stage; not a content date
 */
public record EntitySummary(
        String id,
        String knowledgeId,
        String iterableId,
        String externalId,
        EntityType entityType,
        EntityStatus status,
        String title,
        String uri,
        String checksum,
        Entity.IndexInfo index,
        int retryCount,
        boolean needsReindex,
        Instant createdAt,
        Instant updatedAt) {}
