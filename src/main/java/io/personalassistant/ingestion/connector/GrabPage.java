package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.util.List;

/**
 * One page of results from a grabber. The connector reports the items fetched, the
 * connector-defined {@link CursorPosition} to persist (so the next page resumes correctly), and
 * whether more pages remain. {@code hasMore=false} ends a backward walk ({@code EXHAUSTED}) or
 * parks a forward walk ({@code IDLE}).
 *
 * @param items        items fetched this page (may be empty)
 * @param nextPosition the pagination state to persist after this page
 * @param hasMore      whether the grabber has further pages from this position
 */
public record GrabPage(List<RawItem> items, CursorPosition nextPosition, boolean hasMore) {

    /** Terminal page: no items, keep the current position, no more pages. */
    public static GrabPage end(CursorPosition position) {
        return new GrabPage(List.of(), position, false);
    }
}
