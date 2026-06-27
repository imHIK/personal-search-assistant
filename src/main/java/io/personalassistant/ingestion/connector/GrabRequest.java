package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import java.util.Map;

/**
 * Everything a grabber needs to fetch one page. Passing a request object (rather than a long
 * parameter list) keeps the connector SPI extensible: new optional inputs can be added here
 * without breaking existing connectors.
 *
 * <p>The sub-stream is identified by {@code iterableId} + {@code attributes} (the {@code grab}
 * inputs, e.g. a folder path) rather than a whole {@code SourceIterable}: the driving cursor is
 * self-contained and snapshots exactly these fields, so there is nothing to rebuild. The richer
 * {@link SourceIterable} stays where it belongs — as the output of {@link SourceConnector#discover}.
 *
 * @param knowledge  the configured knowledge (inputs + auth + anchor)
 * @param iterableId identifies the sub-stream to page (a channel, folder, label…)
 * @param attributes connector-specific iterable attributes (the {@code grab} inputs), opaque to core
 * @param direction  backward (history, {@code < anchor}) or forward (incremental, {@code >= anchor})
 * @param position   the connector-defined pagination state to resume from ({@link CursorPosition#start()} on the first page)
 * @param maxItems   a soft cap on items to return; a connector may return fewer (e.g. a fixed API page size)
 */
public record GrabRequest(
        Knowledge knowledge,
        String iterableId,
        Map<String, Object> attributes,
        CursorDirection direction,
        CursorPosition position,
        int maxItems) {

    public GrabRequest {
        attributes = attributes == null ? Map.of() : attributes;
    }

    /** Convenience: true if this is the first page of the iterable in this direction. */
    public boolean isFirstPage() {
        return position == null || position.isStart();
    }
}
