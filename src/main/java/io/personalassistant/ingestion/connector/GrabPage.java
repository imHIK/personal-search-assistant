package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.RawItem;
import java.util.List;

/**
 * One page of results from a grabber. The connector reports the items fetched, the opaque
 * {@code nextPosition} to persist (so the next page resumes correctly), and whether more pages
 * remain. {@code hasMore=false} ends a backward walk ({@code EXHAUSTED}) or parks a forward
 * walk ({@code IDLE}).
 *
 * @param items        items fetched this page (may be empty)
 * @param nextPosition opaque cursor position to persist after this page
 * @param hasMore      whether the grabber has further pages from this position
 */
public record GrabPage(List<RawItem> items, String nextPosition, boolean hasMore) {

    public static GrabPage end(String position) {
        return new GrabPage(List.of(), position, false);
    }
}
