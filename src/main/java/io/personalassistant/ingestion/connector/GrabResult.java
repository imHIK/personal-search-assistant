package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.util.List;

/**
 * {@code hasMore=false} ends a backward walk (EXHAUSTED) or parks a forward one (IDLE); the framework decides
 * which from the cursor's direction.
 */
public record GrabResult(List<RawItem> items, CursorPosition cursor, boolean hasMore) {

    public static GrabResult end(CursorPosition cursor) {
        return new GrabResult(List.of(), cursor, false);
    }
}
