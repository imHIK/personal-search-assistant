package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * THE primary extension point for new integrations: one implementation per data source (local
 * FS, Gmail, Slack…). Implementations live in adapter sub-packages and register themselves with
 * the {@link ConnectorRegistry} via CDI.
 *
 * <p>A connector is also the <em>grabber</em>: it pulls data in a {@link CursorDirection} a page
 * at a time and <strong>owns its own pagination state</strong> (see
 * {@link io.personalassistant.domain.model.CursorPosition}). The ingestion job drives it
 * uniformly — it never inspects the position or branches on direction; the only difference is
 * what re-arms a cursor afterwards (see the indexing design).
 *
 * <p>Connectors get real freedom here: discover whatever iterables make sense (or a single one),
 * paginate however the source works, declare which directions they support, and decide what
 * counts as the boundary (using {@link Knowledge#anchor()}).
 */
public interface SourceConnector {

    /** Which source type this connector handles; used by the registry to select it. */
    SourceType type();

    /**
     * Which ingestion directions this source supports. Defaults to both; a forward-only or
     * backfill-incapable source (e.g. a webhook/stream-only API) can narrow this, and the
     * generic flow will only create the cursors it declares.
     */
    default Set<CursorDirection> supportedDirections() {
        return EnumSet.of(CursorDirection.BACKWARD, CursorDirection.FORWARD);
    }

    /**
     * Whether this source's set of iterables can grow over time (new folders, channels, labels
     * appearing after activation). When {@code true}, the framework periodically re-runs
     * {@link #discover} and creates cursors for any newly-found iterables (existing ones are left
     * untouched). When {@code false} (default), iterables are discovered once at activation.
     */
    default boolean hasDynamicIterables() {
        return false;
    }

    /**
     * This source's default forward-sync cadence — the connector-level tier of schedule resolution
     * (custom &rarr; <strong>connector default</strong> &rarr; global default). Returned when the
     * user has not set a custom schedule on the knowledge. The default is {@link SyncSchedule#NONE}
     * (no opinion — fall through to the global default); a connector with a natural cadence should
     * override it, e.g. a filesystem that has no change-feed might return {@code ofInterval(1 day)}
     * while a webhook-driven API could return a much shorter interval.
     */
    default SyncSchedule defaultSchedule() {
        return SyncSchedule.NONE;
    }

    /** Validate connectivity/credentials/inputs for a configured knowledge. */
    void verify(Knowledge knowledge);

    /**
     * Enumerate the {@link SourceIterable}s for a knowledge (its independently-paged sub-streams).
     * Called during activation (and re-checked per lease); one set of cursors is created per
     * iterable. Return a single iterable if the source has no natural sub-streams.
     */
    List<SourceIterable> discover(Knowledge knowledge);

    /**
     * Grab one page for the given {@link GrabRequest}. The connector resumes from
     * {@code request.position()} and returns the items, the next position to persist, and whether
     * more pages remain.
     */
    GrabPage grab(GrabRequest request);
}
