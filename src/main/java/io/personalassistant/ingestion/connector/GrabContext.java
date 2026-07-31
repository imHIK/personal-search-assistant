package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import java.util.Map;

/**
 * Everything a grabber needs to fetch one page — the direction-free successor to the old
 * {@code GrabRequest}. Rather than a {@code direction} enum plus an anchor the connector had to fold
 * into a query itself, the framework hands over two things:
 *
 * <ul>
 *   <li>{@link #cursor} — the connector-owned, opaque pagination state ({@link CursorPosition}),
 *       {@link CursorPosition#isStart() empty} on the first page. The connector decides what lives
 *       here — a page token, a numeric offset, a {@code (timestamp,id)} keyset, a change-id — and the
 *       core only persists and replays it. This is the sole progress state; the connector round-trips
 *       it and may resume by time to survive an expired token.</li>
 *   <li>{@link #seedWindow} — the {@link TimeWindow} to grab, derived by the framework from the
 *       cursor's direction and the knowledge anchor. It seeds the walk when {@link #cursor} is empty;
 *       the cursor drives every page after that. Its shape also encodes the sense of the walk (a
 *       lower-bounded window is forward, an upper-bounded one backfill), so a connector that must pick
 *       a strategy reads it off the window instead of a direction flag.</li>
 * </ul>
 *
 * <p>Passing a context object (rather than a long parameter list) keeps the SPI extensible: new
 * optional inputs can be added here without breaking existing connectors.
 *
 * @param knowledge  the configured knowledge (inputs + auth + anchor)
 * @param iterableId identifies the sub-stream to page (a channel, folder, label…)
 * @param attributes connector-specific iterable attributes (the {@code grab} inputs), opaque to core
 * @param cursor     connector-defined pagination state to resume from ({@link CursorPosition#start()} on the first page)
 * @param seedWindow the time window to grab, used to seed the walk when {@link #cursor} is empty
 * @param maxItems   a soft cap on items to return; a connector may return fewer (e.g. a fixed API page size)
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

    /** Convenience: true if this is the first page of the iterable (no pagination has happened yet). */
    public boolean isFirstPage() {
        return cursor.isStart();
    }
}
