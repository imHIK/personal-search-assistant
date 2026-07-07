package io.personalassistant.ingestion.connector.localfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.testsupport.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFsConnectorTest {

    private final LocalFsConnector connector = new LocalFsConnector();

    private Knowledge knowledgeAt(Path root, Instant anchor) {
        return TestData.knowledge("kn_fs", SourceType.LOCAL_FS, anchor, Map.of("rootPath", root.toString()));
    }

    private static void write(Path file, String content, Instant modified) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private SourceIterable iterable(Knowledge kn, String id) {
        Optional<SourceIterable> it = connector.discover(kn).stream()
                .filter(i -> i.iterableId().equals(id)).findFirst();
        assertTrue(it.isPresent(), "iterable '" + id + "' should be discovered");
        return it.get();
    }

    private SourceIterable rootIterable(Knowledge kn) {
        return iterable(kn, "root");
    }

    /** A cursor is self-contained, so grab() takes the iterable's id + attributes — not the whole record. */
    private static GrabContext req(Knowledge kn, SourceIterable it, CursorDirection dir, CursorPosition pos, int cap) {
        TimeWindow seed = dir == CursorDirection.BACKWARD
                ? TimeWindow.before(kn.anchor()) : TimeWindow.atOrAfter(kn.anchor());
        return new GrabContext(kn, it.iterableId(), it.attributes(), pos, seed, cap);
    }

    private static List<String> titles(GrabResult page) {
        List<String> out = new ArrayList<>();
        page.items().forEach(i -> out.add(i.title()));
        return out;
    }

    // ---- discovery ---------------------------------------------------------------------------

    @Test
    void discoversRootAndSubdirectoryIterables(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("sub"));
        write(root.resolve("a.txt"), "a", Instant.now());
        write(root.resolve("sub/b.txt"), "b", Instant.now());

        List<SourceIterable> iterables = connector.discover(knowledgeAt(root, Instant.now()));
        assertTrue(iterables.stream().anyMatch(i -> i.iterableId().equals("root")));
        assertTrue(iterables.stream().anyMatch(i -> i.iterableId().equals("sub")));
    }

    // ---- forward: mtime-ordered incremental --------------------------------------------------

    @Test
    void forwardGrabReturnsItemsAtOrAfterAnchorWithPaging(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // two files after the anchor (forward), one before (excluded from forward)
        write(root.resolve("new1.txt"), "one", anchor.plusSeconds(10));
        write(root.resolve("new2.txt"), "two", anchor.plusSeconds(20));
        write(root.resolve("old.txt"), "old", anchor.minusSeconds(60));

        Knowledge kn = knowledgeAt(root, anchor);
        SourceIterable rootIt = rootIterable(kn);

        GrabResult page1 = connector.grab(req(kn, rootIt, CursorDirection.FORWARD, CursorPosition.start(), 1));
        assertEquals(1, page1.items().size());
        assertEquals("new1.txt", page1.items().get(0).title(), "oldest-after-anchor comes first (ascending)");
        assertTrue(page1.hasMore());

        GrabResult page2 = connector.grab(req(kn, rootIt, CursorDirection.FORWARD, page1.cursor(), 1));
        assertEquals(1, page2.items().size());
        assertEquals("new2.txt", page2.items().get(0).title());
        assertFalse(page2.hasMore(), "no forward items remain after the second page");

        RawItem item = page1.items().get(0);
        assertNotNull(item.fileRef());
        assertTrue(item.checksum().contains("mtime:"), "checksum is a cheap (size, mtime) token");
        assertNotNull(item.contentType());
        assertFalse(item.deleted());
    }

    @Test
    void forwardBoundedHeapSelectsOldestCapAndReportsHasMore(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // five files after the anchor, written out of order to exercise the bounded selection
        write(root.resolve("e.txt"), "e", anchor.plusSeconds(50));
        write(root.resolve("a.txt"), "a", anchor.plusSeconds(10));
        write(root.resolve("c.txt"), "c", anchor.plusSeconds(30));
        write(root.resolve("b.txt"), "b", anchor.plusSeconds(20));
        write(root.resolve("d.txt"), "d", anchor.plusSeconds(40));

        Knowledge kn = knowledgeAt(root, anchor);
        SourceIterable rootIt = rootIterable(kn);

        List<String> all = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int guard = 0;
        while (more && guard++ < 10) {
            GrabResult page = connector.grab(req(kn, rootIt, CursorDirection.FORWARD, pos, 2));
            all.addAll(titles(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt"), all,
                "forward pages walk the whole set once, oldest-first, with no gaps or repeats");
    }

    // ---- backward: path-ordered backfill -----------------------------------------------------

    @Test
    void backwardGrabReturnsHistoryBeforeAnchorInPathOrder(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // path order (a before z) is the OPPOSITE of mtime order here, proving we sort by path:
        // a.txt is the oldest, z.txt the newest-of-the-old.
        write(root.resolve("a.txt"), "a", anchor.minusSeconds(200));
        write(root.resolve("z.txt"), "z", anchor.minusSeconds(10));
        write(root.resolve("future.txt"), "future", anchor.plusSeconds(30)); // excluded from backward

        Knowledge kn = knowledgeAt(root, anchor);
        GrabResult page = connector.grab(req(kn, rootIterable(kn), CursorDirection.BACKWARD, CursorPosition.start(), 10));
        assertEquals(List.of("a.txt", "z.txt"), titles(page), "backward backfill is ordered by path, not mtime");
        assertFalse(page.hasMore());
    }

    @Test
    void backwardOrdersDirectoryContentsBeforeSiblingFileComponentWise(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant old = anchor.minusSeconds(100);
        // Under a recursive sub-iterable "docs": a directory "m" and a file "m.txt" are siblings.
        // Component-wise order puts everything under m/ before m.txt, and m.txt before mz.txt.
        write(root.resolve("docs/a.txt"), "a", old);
        write(root.resolve("docs/m/x.txt"), "x", old);
        write(root.resolve("docs/m/y.txt"), "y", old);
        write(root.resolve("docs/m.txt"), "m", old);
        write(root.resolve("docs/mz.txt"), "mz", old);

        Knowledge kn = knowledgeAt(root, anchor);
        GrabResult page = connector.grab(req(kn, iterable(kn, "docs"), CursorDirection.BACKWARD, CursorPosition.start(), 10));
        assertEquals(List.of("a.txt", "x.txt", "y.txt", "m.txt", "mz.txt"), titles(page),
                "m/ contents precede m.txt, which precedes mz.txt (component-wise path order)");
        assertFalse(page.hasMore());
    }

    @Test
    void backwardPagingResumesViaCursorAcrossSubtreesWithoutGapsOrRepeats(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant old = anchor.minusSeconds(100);
        write(root.resolve("docs/a.txt"), "a", old);
        write(root.resolve("docs/m/x.txt"), "x", old);
        write(root.resolve("docs/m/y.txt"), "y", old);
        write(root.resolve("docs/m.txt"), "m", old);
        write(root.resolve("docs/mz.txt"), "mz", old);

        Knowledge kn = knowledgeAt(root, anchor);
        SourceIterable docs = iterable(kn, "docs");

        // Page through in steps of 2; the cursor must skip the consumed spine (e.g. all of m/)
        // and resume exactly where it left off.
        List<String> all = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        int pages = 0;
        boolean more = true;
        while (more && pages < 10) {
            GrabResult page = connector.grab(req(kn, docs, CursorDirection.BACKWARD, pos, 2));
            pages++;
            assertTrue(page.items().size() <= 2);
            all.addAll(titles(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("a.txt", "x.txt", "y.txt", "m.txt", "mz.txt"), all,
                "cursor-driven paging reassembles the full backfill in order, once each");
        assertEquals(3, pages, "5 files at cap=2 should take exactly 3 pages");
    }

    @Test
    void backwardCursorEndsBackfillWhenDrained(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        write(root.resolve("only.txt"), "x", anchor.minusSeconds(5));

        Knowledge kn = knowledgeAt(root, anchor);
        SourceIterable rootIt = rootIterable(kn);

        GrabResult page = connector.grab(req(kn, rootIt, CursorDirection.BACKWARD, CursorPosition.start(), 10));
        assertEquals(List.of("only.txt"), titles(page));
        assertFalse(page.hasMore());

        // resuming past the last item yields nothing and stays terminal
        GrabResult drained = connector.grab(req(kn, rootIt, CursorDirection.BACKWARD, page.cursor(), 10));
        assertTrue(drained.items().isEmpty());
        assertFalse(drained.hasMore());
    }

    @Test
    void rootIterableIsNonRecursiveAndExcludesNestedFiles(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant old = anchor.minusSeconds(50);
        write(root.resolve("top.txt"), "top", old);
        write(root.resolve("sub/deep.txt"), "deep", old);

        Knowledge kn = knowledgeAt(root, anchor);
        GrabResult page = connector.grab(req(kn, rootIterable(kn), CursorDirection.BACKWARD, CursorPosition.start(), 10));
        assertEquals(List.of("top.txt"), titles(page), "the root iterable only emits top-level files");
    }
}
