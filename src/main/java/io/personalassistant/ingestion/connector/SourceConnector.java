package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;

/**
 * THE primary extension point for new integrations: one implementation per data source (local
 * FS, Gmail, Slack…). Implementations live in adapter sub-packages and register themselves with
 * the {@link ConnectorRegistry} via CDI.
 *
 * <p>A connector is also the <em>grabber</em>: it pulls data in a {@link CursorDirection} a page
 * at a time. The ingestion job drives it uniformly — it never branches on direction; the only
 * difference is what re-arms a cursor afterwards (see the indexing design).
 */
public interface SourceConnector {

    /** Which source type this connector handles; used by the registry to select it. */
    SourceType type();

    /** Validate connectivity/credentials/inputs for a configured knowledge. */
    void verify(Knowledge knowledge);

    /**
     * Enumerate the {@link SourceIterable}s for a knowledge (its independently-paged sub-streams).
     * Called once during knowledge activation; one set of cursors is created per iterable.
     */
    java.util.List<SourceIterable> discover(Knowledge knowledge);

    /**
     * Grab one page from an iterable in the given direction, starting at {@code position}.
     *
     * @param knowledge the configured knowledge (provides inputs/auth)
     * @param iterable  the sub-stream to page
     * @param direction backward (history) or forward (incremental); boundary is the anchor
     * @param position  opaque position from the previous page, or null to start
     * @param maxItems  soft cap on items to return in this page
     * @return the page: items + next position + whether more remain
     */
    GrabPage grab(Knowledge knowledge, SourceIterable iterable, CursorDirection direction,
                  String position, int maxItems);
}
