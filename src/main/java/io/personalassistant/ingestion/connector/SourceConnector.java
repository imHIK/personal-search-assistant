package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One per data source, discovered by CDI. The connector is also the grabber: it returns one page at a time
 * and owns its pagination state, never branching on direction (the seed window carries the sense).
 * Token-paged APIs extend TokenWindowGrabber.
 */
public interface SourceConnector {

    SourceType type();

    /** The generic flow creates only the cursors declared here. */
    default Set<CursorDirection> supportedDirections() {
        return EnumSet.of(CursorDirection.BACKWARD, CursorDirection.FORWARD);
    }

    /**
     * When true, discovery re-runs periodically and adds cursors for new iterables; otherwise iterables are
     * discovered once, at activation.
     */
    default boolean hasDynamicIterables() {
        return false;
    }

    /** The connector tier of schedule resolution (custom, connector, global); NONE falls through. */
    default SyncSchedule defaultSchedule() {
        return SyncSchedule.NONE;
    }

    /**
     * The connector tier of retention. Empty is no opinion, and unset everywhere means never expire. Only
     * feed-like sources should set one, longer than the poll cadence: a surviving item is re-created, and
     * re-embedded, by the next walk.
     */
    default Optional<Duration> defaultRetention() {
        return Optional.empty();
    }

    /**
     * Covers only the inputs that change which items belong to an iterable. When it changes on an edit the
     * iterables are re-walked and syncGeneration is bumped. The default hashes all inputs: correct but
     * coarse, so a cosmetic edit triggers a harmless re-walk.
     */
    default String membershipSignature(java.util.Map<String, Object> inputs) {
        return String.valueOf(inputs == null ? java.util.Map.of() : inputs);
    }

    /** When true, a connection is resolved and verified before activation. */
    default boolean requiresConnection() {
        return false;
    }

    /** @throws RuntimeException if the credentials are missing, malformed or rejected */
    default void verifyConnection(Connection connection) {
    }

    void verify(Knowledge knowledge);

    /**
     * One set of cursors is created per iterable; return a single iterable when the source has no
     * sub-streams.
     */
    List<SourceIterable> discover(Knowledge knowledge);

    /**
     * Stateless and idempotent: all pagination state is in the cursor, and a page may be replayed after a
     * crash.
     */
    GrabResult grab(GrabContext context);

    /**
     * Called only for items the runner decided to persist, after the checksum comparison, so costly content
     * can be fetched here rather than in grab. May throw: the page is replayed by the cursor's retry.
     */
    default Entity.Content materialize(Knowledge knowledge, RawItem item) {
        return item.fileRef() != null
                ? Entity.Content.ofFile(item.fileRef())
                : Entity.Content.ofText(item.text());
    }

    /**
     * A connector that stages a copy of its content must declare FETCH_AND_REINDEX: the OS may empty the
     * staging dir long before a re-index reads it.
     */
    default ReindexMode defaultReindexMode() {
        return ReindexMode.REINDEX_ONLY;
    }

    /**
     * Must shape the item exactly as grab would (checksum, raw, metadata, content reference): it is upserted,
     * and any divergence looks like a source-side change. Empty when the item is gone at the source.
     */
    default Optional<RawItem> fetchOne(Knowledge knowledge, Entity entity) {
        throw new IllegalStateException("Connector " + type() + " does not support per-item fetch");
    }
}
