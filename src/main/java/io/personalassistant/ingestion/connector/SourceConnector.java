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
 * THE primary extension point for new integrations: one implementation per data source (local
 * FS, Gmail, Slack…). Implementations live in adapter sub-packages and register themselves with
 * the {@link ConnectorRegistry} via CDI.
 *
 * <p>A connector is also the <em>grabber</em>: it fetches one {@linkplain GrabContext page} at a time
 * and <strong>owns its own pagination state</strong> (see
 * {@link io.personalassistant.domain.model.CursorPosition}) — a page token, an offset, a
 * {@code (timestamp,id)} keyset, whatever the source needs. The grab is direction-free: the framework
 * hands over a {@link TimeWindow} to seed the walk and the opaque cursor to resume from, and the
 * connector just returns the next page + updated cursor + whether more remain. It never branches on a
 * direction; the framework tracks that on the {@link io.personalassistant.domain.model.Cursor} row and
 * uses it only to seed the window and to pick the resting status (backfill drains to {@code EXHAUSTED},
 * incremental parks {@code IDLE}). Rather than implement {@link #grab} by hand, most sources extend a
 * ready-made base: {@link TokenWindowGrabber} for token-paged APIs (Gmail, Drive), or
 * {@link TimeWindowGrabber} for keyset APIs that resume by {@code (timestamp, id)} with no page token.
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

    /**
     * How long this source's items stay useful — the connector-level tier of retention resolution
     * (custom &rarr; <strong>connector default</strong> &rarr; global). The default is
     * {@link Optional#empty()}: no opinion, which for a document source is the right answer, because
     * an unset window at every tier means <em>never expire</em> and a corpus must not delete itself.
     *
     * <p>Only feed-like sources should override this — a job board or a news feed, where an item that
     * has survived the window is stale by definition. Pick a window comfortably longer than the
     * {@linkplain #defaultSchedule() poll cadence}: an item is re-created by the next walk if it still
     * exists at the source, so too short a window just churns re-embeddings.
     */
    default Optional<Duration> defaultRetention() {
        return Optional.empty();
    }

    /**
     * A stable signature over <em>only</em> the {@code inputs} dimensions that change which items
     * belong to an iterable (e.g. {@code fileTypes}, {@code query}, {@code globs}) — never the
     * cosmetic ones (a display label). The edit path compares this before vs. after a change: when
     * it differs, the membership boundary has moved <em>inside</em> the existing iterables, so the
     * framework re-walks them (reset cursors, re-arm backward) to pick up newly-matching items and
     * bumps the knowledge's {@code syncGeneration} to mark the narrowed-out ones.
     *
     * <p>Iterable-level appearance/disappearance is handled separately by discovery reconcile; this
     * hook exists purely for the within-iterable case that a discover-diff cannot see.
     *
     * <p>The default hashes the entire {@code inputs} map: always correct, just coarser — a cosmetic
     * edit will trigger a needless (but harmless, change-detection-skipped) re-walk. A connector that
     * has cosmetic inputs should override this to exclude them.
     */
    default String membershipSignature(java.util.Map<String, Object> inputs) {
        return String.valueOf(inputs == null ? java.util.Map.of() : inputs);
    }

    /**
     * Whether this connector authenticates through a reusable {@link Connection}. Defaults to
     * {@code false} — a no-auth source like the local filesystem needs no connection. Credentialed
     * connectors (Gmail, Drive, Slack…) override this to {@code true}; the knowledge lifecycle then
     * resolves and verifies a connection before activation, and {@code grab} reads its credentials.
     */
    default boolean requiresConnection() {
        return false;
    }

    /**
     * Validate a {@link Connection}'s credentials for this connector — the account-level check that
     * runs once when a connection is created/edited (e.g. call an identity endpoint), independent of
     * any knowledge. Default is a no-op for connectors that {@link #requiresConnection() need none}.
     *
     * @throws RuntimeException if the credentials are missing, malformed, or rejected by the source
     */
    default void verifyConnection(Connection connection) {
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
     * Grab one page for the given {@link GrabContext}. The connector resumes from
     * {@code context.cursor()} (empty on the first page, seeded by {@code context.seedWindow()}) and
     * returns the items, the updated cursor to persist, and whether more pages remain.
     */
    GrabResult grab(GrabContext context);

    /**
     * Produce the content to store for {@code item}. The ingestion runner calls this only for items it
     * has decided to persist — new, changed, or previously failed — <em>after</em> its checksum
     * comparison, so a connector whose content costs an API call can defer that call to here instead
     * of paying it inside {@link #grab} for items that are then skipped.
     *
     * <p>That split is the only way a source can do change-detection before fetching: {@code grab} is
     * stateless and idempotent by contract (invariant 4) and a connector has no access to what is
     * stored, so only the runner can say "this one is unchanged". A connector that can derive its
     * checksum from a cheap listing (Drive's {@code version}) therefore emits items carrying just the
     * checksum and a content <em>reference</em>, and fetches the bytes here.
     *
     * <p>The default carries whatever the item already holds — correct for every connector whose
     * content is cheap by construction: a path into the source itself ({@code LOCAL_FS}), or a body
     * that had to be fetched to know the checksum at all (Gmail's {@code historyId}).
     *
     * <p>Called on the ingestion worker thread inside the cursor's lease, so it may throw: the page is
     * replayed by the cursor's ordinary retry/backoff, which is safe because {@code grab} is
     * idempotent and all pagination state lives on the cursor.
     */
    default Entity.Content materialize(Knowledge knowledge, RawItem item) {
        return item.fileRef() != null
                ? Entity.Content.ofFile(item.fileRef())
                : Entity.Content.ofText(item.text());
    }

    /**
     * Whether a re-index of this source's entities can run from what is already stored, or has to go
     * back to the source first. Defaults to {@link ReindexMode#REINDEX_ONLY}, which is right whenever
     * the stored content outlives nothing: inline text sits in Mongo, and a {@code LOCAL_FS}
     * {@code fileRef} names the user's own file.
     *
     * <p>A connector that stages a <em>copy</em> — Drive writes downloaded bytes into a scratch dir —
     * must declare {@link ReindexMode#FETCH_AND_REINDEX}. The OS is entitled to empty that dir, and a
     * re-index is the one operation that re-reads the file long after the walk that wrote it, so
     * trusting the path there is what dead-lettered every staged file the moment a chunking or
     * embedding change queued them all at once (L11).
     */
    default ReindexMode defaultReindexMode() {
        return ReindexMode.REINDEX_ONLY;
    }

    /**
     * Re-list one already-known item by its {@code externalId}, for the single-entity re-index path.
     * The returned {@link RawItem} must be shaped exactly as {@link #grab} would shape it — same
     * checksum rule, same {@code raw}/{@code metadata}, same content reference — because the caller
     * feeds it straight through {@link #materialize} and upserts the result, so a divergence here
     * makes the next walk see a spurious change.
     *
     * <p>Return {@link Optional#empty()} when the item is gone at the source (Drive trashed it, the
     * local file was removed); the caller tombstones the entity rather than treating it as an error.
     *
     * <p>This is the per-item counterpart to resetting a cursor: a walk lists pages, so re-covering
     * one known item through {@code grab} would mean re-walking everything ahead of it. Sources with
     * no get-by-id leave the default, which throws — they are necessarily
     * {@link ReindexMode#REINDEX_ONLY}, so nothing calls it.
     */
    default Optional<RawItem> fetchOne(Knowledge knowledge, Entity entity) {
        throw new IllegalStateException("Connector " + type() + " does not support per-item fetch");
    }
}
