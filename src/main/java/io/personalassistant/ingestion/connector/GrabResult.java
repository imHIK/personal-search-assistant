package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.util.List;

/**
 * One page of results from a grabber — the successor to {@code GrabPage}. The connector reports the
 * items fetched, the updated connector-owned {@link CursorPosition} to persist (so the next page
 * resumes correctly), and whether more pages remain. {@code hasMore=false} ends a backward walk
 * ({@code EXHAUSTED}) or parks a forward walk ({@code IDLE}); which of the two it means is the
 * framework's call, made from the cursor's direction — not the connector's.
 *
 * @param items   items fetched this page (may be empty)
 * @param cursor  the pagination state to persist after this page
 * @param hasMore whether the grabber has further pages from this position
 */
public record GrabResult(List<RawItem> items, CursorPosition cursor, boolean hasMore) {

    /** Terminal page: no items, keep the current position, no more pages. */
    public static GrabResult end(CursorPosition cursor) {
        return new GrabResult(List.of(), cursor, false);
    }
}
