package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import java.time.Instant;
import java.util.Map;

/**
 * Outbound REST payload for one ingestion cursor — the only honest answer to "has this source
 * finished importing?". A {@code BACKWARD} cursor at {@code EXHAUSTED} means the backfill is
 * complete; a {@code FORWARD} cursor at {@code IDLE} means it is waiting for its next scheduled run.
 *
 * <p>Two fields of {@link Cursor} are deliberately not exposed: {@code attributes} (a snapshot of
 * connector-internal iterable inputs, which can be large and leaks source internals) and
 * {@code lease} (which worker holds it and until when — {@code status == IN_PROGRESS} is the only
 * part of that a caller can act on).
 *
 * @param id         stable cursor id, e.g. {@code "cur_..."}
 * @param iterableId the sub-stream this cursor walks (a folder, label, channel…)
 * @param direction  backward (backfill) or forward (incremental)
 * @param status     operational state
 * @param retryCount consecutive failures so far
 * @param lastError  compact summary of the most recent failure, or null
 * @param lastRunAt  when this cursor last fetched a page, or null if it never has
 * @param fetched    items fetched by this cursor in total
 * @param position   source-defined pagination state, passed through opaquely
 */
public record CursorDto(
        String id,
        String iterableId,
        CursorDirection direction,
        CursorStatus status,
        int retryCount,
        String lastError,
        Instant lastRunAt,
        long fetched,
        Map<String, Object> position) {

    public static CursorDto from(Cursor c) {
        // retry / stats / position can all be null on cursors written by older code — this is a
        // read-only view, so degrade to zero values rather than failing the whole listing.
        Cursor.Retry retry = c.retry() == null ? Cursor.Retry.zero() : c.retry();
        Cursor.Stats stats = c.stats() == null ? Cursor.Stats.zero() : c.stats();
        Map<String, Object> position = c.position() == null || c.position().values() == null
                ? Map.of() : c.position().values();
        return new CursorDto(c.id(), c.iterableId(), c.direction(), c.status(),
                retry.count(), retry.lastError(), stats.lastRunAt(), stats.fetched(), position);
    }
}
