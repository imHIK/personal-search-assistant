package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.time.Instant;
import java.util.List;

/**
 * A ready-made {@link SourceConnector} grab loop for <b>token-paged</b> sources: a source you filter by
 * a time window and page by an <em>opaque continuation token</em> (Gmail, Drive, and most cloud APIs
 * whose list endpoint returns a {@code nextPageToken}). A subclass writes a single, direction-free
 * method — {@link #fetchWindow} — that translates a {@link TimeWindow} into the source's query and
 * returns one page of items plus the source's next token. This base owns everything the old
 * per-connector {@code grabForward}/{@code grabBackward} pair used to hand-roll, so a new connector
 * never learns what forward/backward, floors, or high-water marks are.
 *
 * <ul>
 *   <li><b>Forward (incremental).</b> Seeded at {@code [anchor, +inf)}, it drains the whole window by
 *       token, tracks the newest event-time seen, and on drain advances a high-water floor to it — so
 *       the next scheduled re-arm lists only newer items. Resume within a run is by token; resume
 *       across the {@code IDLE} gap is by the stored floor (a timestamp), so a token expiring between
 *       arms never breaks it.</li>
 *   <li><b>Backward (backfill).</b> Seeded at {@code (-inf, anchor)}, it pages by token until the
 *       source runs dry ({@code hasMore=false} → {@code EXHAUSTED}). It also records the oldest
 *       event-time seen, so a long backfill whose token expires can be re-seeded from that timestamp
 *       (see {@link #resumeFrom}) instead of failing on a dead token.</li>
 * </ul>
 *
 * <p>The sense (forward vs. backward) is read off {@link GrabContext#seedWindow()} — a lower-bounded
 * window is forward, an upper-bounded one backward — so neither this base nor its subclasses branch on
 * a direction enum. The cursor fields ({@code floorMs}/{@code ceilMs}/{@code pageToken}/…) are owned
 * entirely by this base and never surface to the subclass; the subclass only ever sees a resolved
 * {@link TimeWindow} and a page token.
 *
 * <p>Because both walks fully drain their window before advancing, the order the source returns rows in
 * is a free, source-native choice — Gmail can stay newest-first, Drive can order by the window's sense.
 *
 * <h2>Choosing this base vs. {@link TimeWindowGrabber}</h2>
 * Use this base when the source's <em>only</em> continuation handle is an opaque token (it cannot
 * express "give me rows after {@code (timestamp, id)}"). Use {@link TimeWindowGrabber} instead when the
 * source supports keyset queries over a unique, orderable id — that path resumes purely by timestamp,
 * carries no token, and so is immune to token expiry. Sources that fit neither (e.g. a filesystem walk)
 * implement {@link SourceConnector} directly.
 */
public abstract class TokenWindowGrabber implements SourceConnector {

    private static final int DEFAULT_CAP = 100;

    // Cursor fields owned by this base — opaque to subclasses.
    private static final String POS_FLOOR_MS = "floorMs";   // forward: lower bound held across a run
    private static final String POS_CEIL_MS = "ceilMs";     // backward: upper bound held across a run
    private static final String POS_PAGE_TOKEN = "pageToken";
    private static final String POS_MAX_MS = "maxMs";       // forward: newest event-time seen this run
    private static final String POS_MIN_MS = "minMs";       // backward: oldest event-time seen this run

    /** One page from the source: the mapped items, and the source's continuation token (null when drained). */
    public record Page(List<RawItem> items, String nextPageToken) {
        public Page {
            items = items == null ? List.of() : items;
        }

        /** A terminal page: no items, no continuation. */
        public static Page end() {
            return new Page(List.of(), null);
        }
    }

    /**
     * Fetch one page of items whose event-time falls in {@code window}, resuming from
     * {@code pageToken} (null on the first page of a run), in whatever order is cheapest for the
     * source. This is the only pagination code a subclass writes: translate {@code window} to a query
     * (an {@linkplain TimeWindow#hasLo() open} bound means "no predicate on that side"), call the API,
     * map the rows to {@link RawItem}s, and return them plus the source's {@code nextPageToken} (null
     * when the window is fully drained).
     */
    protected abstract Page fetchWindow(GrabContext ctx, TimeWindow window, String pageToken, int cap);

    /**
     * The event-time of an item — the value {@code window} filters on and the high-/low-water marks
     * track. Defaults to {@link RawItem#modifiedAt()}; override if a source times items by a different
     * field.
     */
    protected Instant eventTime(RawItem item) {
        return item.modifiedAt();
    }

    @Override
    public final GrabResult grab(GrabContext ctx) {
        int cap = ctx.maxItems() > 0 ? ctx.maxItems() : DEFAULT_CAP;
        // Window shape is the sense of the walk: lower-bounded => forward, otherwise backward.
        return ctx.seedWindow().hasLo() ? forward(ctx, cap) : backward(ctx, cap);
    }

    // ---- forward: drain [floor, +inf), advance the high-water floor on drain -------------------

    private GrabResult forward(GrabContext ctx, int cap) {
        CursorPosition c = ctx.cursor();
        long floorMs = c.getLong(POS_FLOOR_MS, ctx.seedWindow().lo().toEpochMilli());
        String token = c.getString(POS_PAGE_TOKEN); // null => fresh run for this arm
        long runMax = c.getLong(POS_MAX_MS, floorMs);

        Page page = fetchWindow(ctx, TimeWindow.atOrAfter(Instant.ofEpochMilli(floorMs)), token, cap);
        runMax = Math.max(runMax, maxEventTime(page.items(), floorMs));

        if (page.nextPageToken() != null) {
            // more pages this run: hold the floor, carry the token + running max forward
            CursorPosition next = CursorPosition.builder()
                    .put(POS_FLOOR_MS, floorMs)
                    .put(POS_PAGE_TOKEN, page.nextPageToken())
                    .put(POS_MAX_MS, runMax)
                    .build();
            return new GrabResult(page.items(), next, true);
        }
        // run drained: advance the floor to the newest we saw so the next arm only lists newer items
        return new GrabResult(page.items(),
                CursorPosition.builder().put(POS_FLOOR_MS, runMax).build(), false);
    }

    // ---- backward: drain (-inf, ceil) by token until history runs out --------------------------

    private GrabResult backward(GrabContext ctx, int cap) {
        CursorPosition c = ctx.cursor();
        long ceilMs = c.getLong(POS_CEIL_MS, ctx.seedWindow().hi().toEpochMilli());
        String token = c.getString(POS_PAGE_TOKEN);
        long runMin = c.getLong(POS_MIN_MS, ceilMs);

        Page page = fetchWindow(ctx, TimeWindow.before(Instant.ofEpochMilli(ceilMs)), token, cap);
        runMin = Math.min(runMin, minEventTime(page.items(), ceilMs));

        if (page.nextPageToken() != null) {
            CursorPosition next = CursorPosition.builder()
                    .put(POS_CEIL_MS, ceilMs)
                    .put(POS_PAGE_TOKEN, page.nextPageToken())
                    .put(POS_MIN_MS, runMin)
                    .build();
            return new GrabResult(page.items(), next, true);
        }
        // history drained -> terminal (EXHAUSTED). Keep ceil + the oldest seen for debuggability and
        // so a token-free re-seed (resumeFrom) has a low-water mark to fall back to.
        CursorPosition next = CursorPosition.builder()
                .put(POS_CEIL_MS, ceilMs)
                .put(POS_MIN_MS, runMin)
                .build();
        return new GrabResult(page.items(), next, false);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private long maxEventTime(List<RawItem> items, long floor) {
        long max = floor;
        for (RawItem item : items) {
            Instant t = eventTime(item);
            if (t != null && t.toEpochMilli() > max) {
                max = t.toEpochMilli();
            }
        }
        return max;
    }

    private long minEventTime(List<RawItem> items, long ceil) {
        long min = ceil;
        for (RawItem item : items) {
            Instant t = eventTime(item);
            if (t != null && t.toEpochMilli() < min) {
                min = t.toEpochMilli();
            }
        }
        return min;
    }

    /**
     * The timestamp a token-free resume should fall back to for {@code cursor} — the stored high-water
     * floor (forward) or low-water ceiling (backward). Exposed so a token-staleness policy, or a
     * subclass whose page token has expired mid-run, can re-seed the walk by time instead of failing on
     * a dead token: re-list from this instant and let the ingestion runner's checksum change-detection
     * drop the boundary overlap. Returns null when the cursor holds no such mark yet (a fresh run).
     */
    protected Instant resumeFrom(CursorPosition cursor) {
        Long floor = cursor.getLong(POS_FLOOR_MS);
        if (floor != null) {
            return Instant.ofEpochMilli(floor);
        }
        Long min = cursor.getLong(POS_MIN_MS);
        return min == null ? null : Instant.ofEpochMilli(min);
    }
}
