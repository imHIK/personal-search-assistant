package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;

/**
 * Read-model projection of an {@link Entity} for listing views (the console's entity browser).
 *
 * <p>Deliberately not an {@code Entity}: {@link Entity#raw} is the complete source payload and
 * {@code content.text} is the whole extracted body — together they are the bulk of an entity
 * document, and a listing never needs either. Paging fifty full entities would move megabytes to
 * render a table, so the storage adapter projects only the fields below.
 *
 * @param id           internal id, e.g. {@code "ent_..."}
 * @param knowledgeId  owning knowledge
 * @param externalId   natural key within the source (path, message id…)
 * @param entityType   coarse classification (FILE / MESSAGE / …)
 * @param status       indexing lifecycle state
 * @param title        display title lifted from {@code metadata.title}, or null
 * @param uri          citation locator lifted from {@code metadata.uri}, or null
 * @param checksum     content hash for change detection
 * @param index        rollup of what was last indexed (chunk count, model, timestamp, error)
 * @param retryCount   indexing retry attempts so far
 * @param needsReindex set when content changed or config bumped; forces re-indexing
 * @param updatedAt    last-modified timestamp — the listing sort key
 */
public record EntitySummary(
        String id,
        String knowledgeId,
        String externalId,
        EntityType entityType,
        EntityStatus status,
        String title,
        String uri,
        String checksum,
        Entity.IndexInfo index,
        int retryCount,
        boolean needsReindex,
        Instant updatedAt) {}
