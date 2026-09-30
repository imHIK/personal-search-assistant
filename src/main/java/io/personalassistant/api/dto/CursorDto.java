package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import java.time.Instant;
import java.util.Map;

/**
 * @param iterableName null on cursors written before names were stored
 * @param nextAttemptAt when a RATE_LIMITED cursor may run again; null for any other status, and in the past
 *                      once the hold has elapsed
 */
public record CursorDto(
        String id,
        String iterableId,
        String iterableName,
        CursorDirection direction,
        CursorStatus status,
        int retryCount,
        String lastError,
        Instant nextAttemptAt,
        Instant lastRunAt,
        long fetched,
        Map<String, Object> position) {

    public static CursorDto from(Cursor c) {
        // Older cursors may lack retry, stats and position; a read-only view degrades to zero values.
        Cursor.Retry retry = c.retry() == null ? Cursor.Retry.zero() : c.retry();
        Cursor.Stats stats = c.stats() == null ? Cursor.Stats.zero() : c.stats();
        Map<String, Object> position = c.position() == null || c.position().values() == null
                ? Map.of() : c.position().values();
        return new CursorDto(c.id(), c.iterableId(), c.iterableName(), c.direction(), c.status(),
                retry.count(), retry.lastError(), retry.nextAttemptAt(),
                stats.lastRunAt(), stats.fetched(), position);
    }
}
