package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;

/**
 * Everything a grabber needs to fetch one page. Passing a request object (rather than a long
 * parameter list) keeps the connector SPI extensible: new optional inputs can be added here
 * without breaking existing connectors.
 *
 * @param knowledge the configured knowledge (inputs + auth + anchor)
 * @param iterable  the sub-stream to page
 * @param direction backward (history, {@code < anchor}) or forward (incremental, {@code >= anchor})
 * @param position  the connector-defined pagination state to resume from ({@link CursorPosition#start()} on the first page)
 * @param maxItems  a soft cap on items to return; a connector may return fewer (e.g. a fixed API page size)
 */
public record GrabRequest(
        Knowledge knowledge,
        SourceIterable iterable,
        CursorDirection direction,
        CursorPosition position,
        int maxItems) {

    /** Convenience: true if this is the first page of the iterable in this direction. */
    public boolean isFirstPage() {
        return position == null || position.isStart();
    }
}
