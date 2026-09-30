package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.RawItem;
import java.time.Instant;
import java.util.List;

/**
 * The grab loop for token-paged sources; a subclass implements only fetchWindow. Forward drains
 * {@code [floor, +inf)} by token and, once drained, advances the floor to the newest event-time seen, so a
 * resume across the IDLE gap is by timestamp and an expired token cannot break it. Backward pages by token
 * until the source runs dry, recording the oldest time seen so an expired token can be re-seeded
 * (resumeFrom). The cursor fields belong to this base alone.
 */
public abstract class TokenWindowGrabber implements SourceConnector {

    private static final int DEFAULT_CAP = 100;

    private static final String POS_FLOOR_MS = "floorMs";   // forward: lower bound held across a run
    private static final String POS_CEIL_MS = "ceilMs";     // backward: upper bound held across a run
    private static final String POS_PAGE_TOKEN = "pageToken";
    private static final String POS_MAX_MS = "maxMs";       // forward: newest event-time seen this run
    private static final String POS_MIN_MS = "minMs";       // backward: oldest event-time seen this run

    /** @param nextPageToken null when the window is drained */
    public record Page(List<RawItem> items, String nextPageToken) {
        public Page {
            items = items == null ? List.of() : items;
        }

        public static Page end() {
            return new Page(List.of(), null);
        }
    }

    /**
     * The only pagination code a subclass writes: translate the window into a query (an open bound means no
     * predicate on that side), map the rows, and return the next token, null once drained.
     */
    protected abstract Page fetchWindow(GrabContext ctx, TimeWindow window, String pageToken, int cap);

    /** What the window filters on and the watermarks track; defaults to modifiedAt. */
    protected Instant eventTime(RawItem item) {
        return item.modifiedAt();
    }

    @Override
    public final GrabResult grab(GrabContext ctx) {
        int cap = ctx.maxItems() > 0 ? ctx.maxItems() : DEFAULT_CAP;
        // The window's shape is the walk's sense: lower-bounded means forward.
        return ctx.seedWindow().hasLo() ? forward(ctx, cap) : backward(ctx, cap);
    }

    private GrabResult forward(GrabContext ctx, int cap) {
        CursorPosition c = ctx.cursor();
        long floorMs = c.getLong(POS_FLOOR_MS, ctx.seedWindow().lo().toEpochMilli());
        String token = c.getString(POS_PAGE_TOKEN); // null => fresh run for this arm
        long runMax = c.getLong(POS_MAX_MS, floorMs);

        Page page = fetchWindow(ctx, TimeWindow.atOrAfter(Instant.ofEpochMilli(floorMs)), token, cap);
        runMax = Math.max(runMax, maxEventTime(page.items(), floorMs));

        if (page.nextPageToken() != null) {
            // More pages this run: hold the floor, carry the token and running max.
            CursorPosition next = CursorPosition.builder()
                    .put(POS_FLOOR_MS, floorMs)
                    .put(POS_PAGE_TOKEN, page.nextPageToken())
                    .put(POS_MAX_MS, runMax)
                    .build();
            return new GrabResult(page.items(), next, true);
        }
        // Drained: advance the floor to the newest seen, so the next arm lists only newer items.
        return new GrabResult(page.items(),
                CursorPosition.builder().put(POS_FLOOR_MS, runMax).build(), false);
    }

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
        // Drained: terminal. Keep the ceiling and the oldest seen, so a token-free re-seed has a low-water
        // mark.
        CursorPosition next = CursorPosition.builder()
                .put(POS_CEIL_MS, ceilMs)
                .put(POS_MIN_MS, runMin)
                .build();
        return new GrabResult(page.items(), next, false);
    }

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
     * Where a token-free resume falls back to: the forward floor or the backward ceiling. Re-listing from it
     * overlaps, and checksum change-detection drops the overlap. Null for a fresh run.
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
