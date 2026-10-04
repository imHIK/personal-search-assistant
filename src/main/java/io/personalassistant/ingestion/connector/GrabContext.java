package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import java.util.Map;

/**
 * @param cursor connector-owned pagination state, empty on the first page; the only progress state
 * @param seedWindow seeds the walk while the cursor is empty; its shape is the walk's sense (lower-bounded
 *                   forward, upper-bounded backfill)
 * @param maxItems a soft cap; a connector may return fewer
 */
public record GrabContext(
        Knowledge knowledge,
        String iterableId,
        Map<String, Object> attributes,
        CursorPosition cursor,
        TimeWindow seedWindow,
        int maxItems) {

    public GrabContext {
        attributes = attributes == null ? Map.of() : attributes;
        cursor = cursor == null ? CursorPosition.start() : cursor;
    }

    public boolean isFirstPage() {
        return cursor.isStart();
    }
}
