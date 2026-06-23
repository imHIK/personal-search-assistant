package io.personalassistant.ingestion.connector.localfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.testsupport.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    @Test
    void discoversRootAndSubdirectoryIterables(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("sub"));
        write(root.resolve("a.txt"), "a", Instant.now());
        write(root.resolve("sub/b.txt"), "b", Instant.now());

        List<SourceIterable> iterables = connector.discover(knowledgeAt(root, Instant.now()));
        assertTrue(iterables.stream().anyMatch(i -> i.iterableId().equals("root")));
        assertTrue(iterables.stream().anyMatch(i -> i.iterableId().equals("sub")));
    }

    @Test
    void forwardGrabReturnsItemsAtOrAfterAnchorWithPaging(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // two files after the anchor (forward), one before (excluded from forward)
        write(root.resolve("new1.txt"), "one", anchor.plusSeconds(10));
        write(root.resolve("new2.txt"), "two", anchor.plusSeconds(20));
        write(root.resolve("old.txt"), "old", anchor.minusSeconds(60));

        Knowledge kn = knowledgeAt(root, anchor);
        SourceIterable rootIt = rootIterable(kn);

        GrabPage page1 = connector.grab(kn, rootIt, CursorDirection.FORWARD, null, 1);
        assertEquals(1, page1.items().size());
        assertEquals("new1.txt", page1.items().get(0).title(), "oldest-after-anchor comes first (ascending)");
        assertTrue(page1.hasMore());

        GrabPage page2 = connector.grab(kn, rootIt, CursorDirection.FORWARD, page1.nextPosition(), 1);
        assertEquals(1, page2.items().size());
        assertEquals("new2.txt", page2.items().get(0).title());
        assertFalse(page2.hasMore(), "no forward items remain after the second page");

        RawItem item = page1.items().get(0);
        assertNotNull(item.fileRef());
        assertTrue(item.checksum().startsWith("sha256:"));
        assertNotNull(item.contentType());
        assertFalse(item.deleted());
    }

    @Test
    void backwardGrabReturnsHistoryBeforeAnchorNewestFirst(@TempDir Path root) throws IOException {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        write(root.resolve("older.txt"), "older", anchor.minusSeconds(120));
        write(root.resolve("newer.txt"), "newer", anchor.minusSeconds(30));
        write(root.resolve("future.txt"), "future", anchor.plusSeconds(30)); // excluded from backward

        Knowledge kn = knowledgeAt(root, anchor);
        GrabPage page = connector.grab(kn, rootIterable(kn), CursorDirection.BACKWARD, null, 10);
        assertEquals(2, page.items().size());
        assertEquals("newer.txt", page.items().get(0).title(), "backward walks newest-of-the-old first");
        assertEquals("older.txt", page.items().get(1).title());
        assertFalse(page.hasMore());
    }

    private SourceIterable rootIterable(Knowledge kn) {
        Optional<SourceIterable> root = connector.discover(kn).stream()
                .filter(i -> i.iterableId().equals("root")).findFirst();
        assertTrue(root.isPresent());
        return root.get();
    }
}
