package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A ready-made {@link SourceConnector} grab loop for <b>keyset-paged</b> sources: a source that can
 * return "the next rows ordered by {@code (event-time, id)}, after a given key" (an {@code updated_since}
 * query plus a stable, unique, orderable id — Jira, Linear, most SQL/CDC-backed APIs). The cursor is a
 * {@code (timestamp, id)} keyset — <em>no opaque page token</em> — so pagination resumes purely by
 * time and is immune to token expiry across an arbitrarily long {@code IDLE} gap or a slow backfill.
 * That robustness is the whole reason to prefer this base over {@link TokenWindowGrabber} when the
 * source can support it.
 *
 * <p>A subclass writes a single, direction-free method — {@link #fetchSlice} — that runs one keyset
 * query. Forward and backward collapse into that one method because the only differences are handled
 * by this base:
 * <ul>
 *   <li><b>Forward (incremental).</b> Ascending {@code (event-time, id)}, seeded at {@code [anchor, +inf)}.
 *       The keyset cursor advances page by page; when a short page arrives the walk is caught up
 *       ({@code hasMore=false} → {@code IDLE}) and a later re-arm simply resumes from the same keyset,
 *       picking up anything newer.</li>
 *   <li><b>Backward (backfill).</b> Descending {@code (event-time, id)}, seeded at {@code (-inf, anchor)};
 *       runs until a short page arrives ({@code EXHAUSTED}).</li>
 * </ul>
 * The sense is read off {@link GrabContext#seedWindow()} (lower-bounded = forward, upper-bounded =
 * backward), so the subclass never branches on a direction, and the {@code (keyTs, keyId)} cursor
 * fields are owned entirely by this base.
 *
 * <h2>Correctness contract for {@link #fetchSlice}</h2>
 * The slice MUST be ordered per {@link KeysetQuery#ascending()} and contain only rows <em>strictly
 * beyond</em> {@link KeysetQuery#after()} in that order — i.e. the connector encodes the keyset into its
 * query, e.g. {@code WHERE (t > :ts) OR (t = :ts AND id > :id)} ascending. Encoding the id tiebreak is
 * what lets the walk step past a cluster of rows sharing one timestamp that is larger than a page; a
 * bare {@code t >= :ts} filter would re-list the cluster forever. (As a safety net the base still drops
 * any boundary row not strictly beyond the key, so an inclusive filter merely costs a little rework, it
 * does not double-emit.) A full {@code cap}-sized slice signals "more may remain".
 *
 * <p>Sources whose only continuation handle is an opaque token (no orderable id to key on — Gmail,
 * Drive) belong on {@link TokenWindowGrabber}; sources that fit neither implement {@link SourceConnector}
 * directly.
 */
public abstract class TimeWindowGrabber implements SourceConnector {

    private static final int DEFAULT_CAP = 100;

    // Cursor fields owned by this base — opaque to subclasses.
    private static final String POS_KEY_TS = "keyTs";   // event-time (epoch ms) of the last row emitted
    private static final String POS_KEY_ID = "keyId";   // external id of the last row emitted (tiebreak)

    /** A resume point in the source's total order: the {@code (event-time, id)} of the last row emitted. */
    public record Keyset(Instant timestamp, String id) {
    }

    /**
     * One keyset page request handed to {@link #fetchSlice}.
     *
     * @param window    the anchor-relative bound ({@code [anchor,+inf)} forward, {@code (-inf,anchor)} backward)
     * @param after     resume strictly beyond this key, or null on the first page
     * @param ascending true for the forward walk (oldest-first), false for the backfill (newest-first)
     * @param cap       the maximum number of rows to return
     */
    public record KeysetQuery(TimeWindow window, Keyset after, boolean ascending, int cap) {
    }

    /**
     * Run one keyset query: return up to {@link KeysetQuery#cap()} items inside
     * {@link KeysetQuery#window()}, ordered per {@link KeysetQuery#ascending()}, strictly beyond
     * {@link KeysetQuery#after()}. This is the only pagination code a subclass writes.
     */
    protected abstract List<RawItem> fetchSlice(GrabContext ctx, KeysetQuery query);

    /**
     * The event-time used as the primary keyset component and window filter. Defaults to
     * {@link RawItem#modifiedAt()}; override if a source keys items by a different field.
     */
    protected Instant eventTime(RawItem item) {
        return item.modifiedAt();
    }

    @Override
    public final GrabResult grab(GrabContext ctx) {
        int cap = ctx.maxItems() > 0 ? ctx.maxItems() : DEFAULT_CAP;
        boolean ascending = ctx.seedWindow().hasLo(); // lower-bounded window => forward => oldest-first
        Keyset after = ctx.cursor().isStart() ? null
                : new Keyset(Instant.ofEpochMilli(ctx.cursor().getLong(POS_KEY_TS, 0L)),
                             ctx.cursor().getString(POS_KEY_ID));

        List<RawItem> raw = fetchSlice(ctx, new KeysetQuery(ctx.seedWindow(), after, ascending, cap));

        // Safety net: keep only rows strictly beyond the resume key, in case the source used an
        // inclusive bound and re-listed the boundary row.
        List<RawItem> emitted = new ArrayList<>(raw.size());
        for (RawItem item : raw) {
            if (after == null || beyond(keysetOf(item), after, ascending)) {
                emitted.add(item);
            }
        }

        boolean hasMore = raw.size() >= cap; // a full page suggests more remain
        // Advance to the last RAW row (not the last emitted) so the key always moves forward, even when
        // an inclusive re-list produced only boundary duplicates this page.
        Keyset advanced = raw.isEmpty() ? after : keysetOf(raw.get(raw.size() - 1));
        CursorPosition next = advanced == null
                ? ctx.cursor()
                : CursorPosition.builder()
                        .put(POS_KEY_TS, advanced.timestamp().toEpochMilli())
                        .put(POS_KEY_ID, advanced.id())
                        .build();
        return new GrabResult(emitted, next, hasMore);
    }

    private Keyset keysetOf(RawItem item) {
        Instant t = eventTime(item);
        return new Keyset(t == null ? Instant.EPOCH : t, item.externalId());
    }

    /** Ascending: strictly greater than {@code after}; descending: strictly less than. */
    private static boolean beyond(Keyset key, Keyset after, boolean ascending) {
        int cmp = compare(key, after);
        return ascending ? cmp > 0 : cmp < 0;
    }

    private static int compare(Keyset a, Keyset b) {
        int c = a.timestamp().compareTo(b.timestamp());
        if (c != 0) {
            return c;
        }
        String ai = a.id() == null ? "" : a.id();
        String bi = b.id() == null ? "" : b.id();
        return ai.compareTo(bi);
    }
}
