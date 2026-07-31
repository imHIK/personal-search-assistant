package io.personalassistant.ingestion.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reference test for {@link TimeWindowGrabber} — the keyset base. It doubles as the worked example a
 * future keyset connector (Jira/Linear/a DB source) can copy: {@link FakeKeysetSource} is a minimal,
 * <em>correct</em> keyset source (it encodes the {@code (event-time, id)} tiebreak into its query), and
 * the assertions pin the direction-free, token-free behavior the base guarantees.
 */
class TimeWindowGrabberTest {

    private final FakeKeysetSource source = new FakeKeysetSource();

    private Knowledge knowledge(Instant anchor) {
        return TestData.knowledge("kn_keyset", SourceType.LOCAL_FS, anchor, Map.of());
    }

    /** Build the seed window the framework would derive from a cursor's direction + the anchor. */
    private GrabContext ctx(Knowledge kn, CursorDirection dir, CursorPosition cursor, int cap) {
        TimeWindow seed = dir == CursorDirection.BACKWARD
                ? TimeWindow.before(kn.anchor()) : TimeWindow.atOrAfter(kn.anchor());
        return new GrabContext(kn, "it", Map.of(), cursor, seed, cap);
    }

    private static List<String> ids(GrabResult page) {
        List<String> out = new ArrayList<>();
        page.items().forEach(i -> out.add(i.externalId()));
        return out;
    }

    // ---- forward: ascending, resumes by keyset (no token) ------------------------------------

    @Test
    void forwardReturnsAscendingAtOrAfterAnchorAndResumesByKeyset() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source.add("a", anchor.plusSeconds(10)).add("b", anchor.plusSeconds(20))
              .add("c", anchor.plusSeconds(30)).add("old", anchor.minusSeconds(60));
        Knowledge kn = knowledge(anchor);

        GrabResult page = source.grab(ctx(kn, CursorDirection.FORWARD, CursorPosition.start(), 10));
        assertEquals(List.of("a", "b", "c"), ids(page), "oldest-first, excludes pre-anchor");
        assertFalse(page.hasMore());
        assertEquals("c", page.cursor().getString("keyId"), "cursor keys on the last row emitted");

        // A newer row arrives; re-grabbing from the returned keyset cursor picks it up — no page token.
        source.add("d", anchor.plusSeconds(40));
        GrabResult next = source.grab(ctx(kn, CursorDirection.FORWARD, page.cursor(), 10));
        assertEquals(List.of("d"), ids(next), "resumes strictly after the keyset, catching new rows");
    }

    @Test
    void forwardPaginatesInKeysetOrderOnceEach() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 5; i++) {
            source.add("m" + i, anchor.plusSeconds(i * 10L));
        }
        Knowledge kn = knowledge(anchor);

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int guard = 0;
        while (more && guard++ < 20) {
            GrabResult page = source.grab(ctx(kn, CursorDirection.FORWARD, pos, 2));
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("m1", "m2", "m3", "m4", "m5"), collected, "ascending, once each, no gaps");
    }

    // ---- backward: descending backfill then exhausts -----------------------------------------

    @Test
    void backwardReturnsDescendingBeforeAnchorThenExhausts() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source.add("h1", anchor.minusSeconds(10)).add("h2", anchor.minusSeconds(20))
              .add("h3", anchor.minusSeconds(30)).add("future", anchor.plusSeconds(30));
        Knowledge kn = knowledge(anchor);

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int pages = 0;
        while (more && pages < 10) {
            GrabResult page = source.grab(ctx(kn, CursorDirection.BACKWARD, pos, 2));
            pages++;
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("h1", "h2", "h3"), collected, "newest-of-old first, excludes post-anchor");
    }

    // ---- the keyset payoff: a same-second cluster larger than a page does not stall ----------

    @Test
    void sameTimestampClusterLargerThanCapDoesNotStall() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant t = anchor.plusSeconds(10);
        source.add("A", t).add("B", t).add("C", t).add("D", t); // four rows sharing one timestamp
        Knowledge kn = knowledge(anchor);

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int guard = 0;
        while (more && guard++ < 20) {
            GrabResult page = source.grab(ctx(kn, CursorDirection.FORWARD, pos, 2));
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("A", "B", "C", "D"), collected,
                "the (timestamp,id) tiebreak steps past a cluster bigger than the page — no stall, no repeats");
        assertTrue(guard <= 4, "terminates promptly rather than looping");
    }

    /**
     * A minimal correct keyset source: an in-memory list ordered by {@code (modifiedAt, externalId)}
     * whose {@link #fetchSlice} honors the base's contract — window bounds, strict-after the keyset, in
     * the requested order, capped. This is the shape a real {@code updated_since}+id API would take.
     */
    static final class FakeKeysetSource extends TimeWindowGrabber {

        private static final Comparator<RawItem> BY_KEY =
                Comparator.comparing(RawItem::modifiedAt).thenComparing(RawItem::externalId);

        private final List<RawItem> store = new ArrayList<>();

        FakeKeysetSource add(String id, Instant ts) {
            store.add(new RawItem(id, EntityType.MESSAGE, "text/plain", id, "uri:" + id, "sum:" + id,
                    ts, Map.of(), "body:" + id, null, Map.of("title", id), false));
            return this;
        }

        @Override
        public SourceType type() {
            return SourceType.LOCAL_FS;
        }

        @Override
        public void verify(Knowledge knowledge) {
        }

        @Override
        public List<SourceIterable> discover(Knowledge knowledge) {
            return List.of(new SourceIterable("it", "it", Map.of()));
        }

        @Override
        protected List<RawItem> fetchSlice(GrabContext ctx, KeysetQuery query) {
            return store.stream()
                    .filter(it -> inWindow(it.modifiedAt(), query.window()))
                    .filter(it -> query.after() == null || beyond(it, query.after(), query.ascending()))
                    .sorted(query.ascending() ? BY_KEY : BY_KEY.reversed())
                    .limit(query.cap())
                    .toList();
        }

        private static boolean inWindow(Instant t, TimeWindow w) {
            if (w.hasLo() && t.isBefore(w.lo())) {
                return false;
            }
            return !w.hasHi() || t.isBefore(w.hi()); // hi is exclusive
        }

        private static boolean beyond(RawItem it, Keyset after, boolean ascending) {
            int cmp = compare(it.modifiedAt(), it.externalId(), after.timestamp(), after.id());
            return ascending ? cmp > 0 : cmp < 0;
        }

        private static int compare(Instant at, String aid, Instant bt, String bid) {
            int c = at.compareTo(bt);
            return c != 0 ? c : aid.compareTo(bid);
        }
    }
}
