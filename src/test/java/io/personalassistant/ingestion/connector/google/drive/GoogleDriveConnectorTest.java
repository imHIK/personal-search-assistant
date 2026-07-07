package io.personalassistant.ingestion.connector.google.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.testsupport.TestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoogleDriveConnectorTest {

    private final FakeDriveApi api = new FakeDriveApi();
    private final GoogleAccessTokens tokens = conn -> "test-token";
    private final Connection connection = TestData.connection("conn_drive", SourceType.GOOGLE_DRIVE, true,
            Map.of("accessToken", "test-token"));
    private final ConnectionResolver connections = kn -> connection;
    private GoogleDriveConnector connector;

    private GoogleDriveConnector connector(Path scratch) {
        GoogleDriveConnector c = new GoogleDriveConnector(api, tokens, connections);
        c.downloadDir = scratch.toString();
        c.maxFileBytes = 26_214_400L;
        c.maxFolders = 500;
        return c;
    }

    private Knowledge knowledge(Instant anchor, Map<String, Object> inputs) {
        return TestData.knowledge("kn_drive", SourceType.GOOGLE_DRIVE, anchor, inputs);
    }

    private static GrabContext req(Knowledge kn, SourceIterable it, CursorDirection dir, CursorPosition pos, int cap) {
        TimeWindow seed = dir == CursorDirection.BACKWARD
                ? TimeWindow.before(kn.anchor()) : TimeWindow.atOrAfter(kn.anchor());
        return new GrabContext(kn, it.iterableId(), it.attributes(), pos, seed, cap);
    }

    private static List<String> ids(GrabResult page) {
        List<String> out = new ArrayList<>();
        page.items().forEach(i -> out.add(i.externalId()));
        return out;
    }

    private SourceIterable iterable(List<SourceIterable> its, String id) {
        Optional<SourceIterable> it = its.stream().filter(i -> i.iterableId().equals(id)).findFirst();
        assertTrue(it.isPresent(), "iterable '" + id + "' should be discovered");
        return it.get();
    }

    // ---- discovery ---------------------------------------------------------------------------

    @Test
    void discoversFolderTreeBreadthFirst(@TempDir Path scratch) {
        connector = connector(scratch);
        // root -> f1 -> f2 ; plus a file that must NOT become an iterable
        api.folder("f1", "Projects", "root");
        api.folder("f2", "2026", "f1");
        api.binary("doc", "notes.txt", "text/plain", "f1", Instant.now().toEpochMilli(), 1, "hi".getBytes());

        List<SourceIterable> iterables = connector.discover(knowledge(Instant.now(), Map.of()));
        assertEquals("My Drive", iterable(iterables, "root").displayName());
        assertEquals("Projects", iterable(iterables, "f1").displayName());
        assertEquals("2026", iterable(iterables, "f2").displayName());
        assertEquals(3, iterables.size(), "only folders become iterables, walked recursively");
    }

    // ---- forward: ascending high-water walk ---------------------------------------------------

    @Test
    void forwardReturnsFilesAtOrAfterAnchorOldestFirstAndAdvancesHighWater(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.nativeDoc("d1", "Doc One", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(10).toEpochMilli(), 3, "first doc body");
        api.nativeDoc("d2", "Doc Two", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(20).toEpochMilli(), 2, "second doc body");
        api.nativeDoc("old", "Old", "application/vnd.google-apps.document", "root",
                anchor.minusSeconds(60).toEpochMilli(), 1, "old body");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");

        GrabResult page = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10));
        assertEquals(List.of("d1", "d2"), ids(page), "oldest-first ascending, excludes pre-anchor");
        assertFalse(page.hasMore());

        api.nativeDoc("d3", "Doc Three", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(40).toEpochMilli(), 1, "third doc body");
        GrabResult next = connector.grab(req(kn, root, CursorDirection.FORWARD, page.cursor(), 10));
        assertTrue(ids(next).contains("d3"), "high-water floor advanced to pick up the newer file");
    }

    @Test
    void forwardPagesThroughAllMatchesOnce(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 5; i++) {
            api.nativeDoc("d" + i, "Doc " + i, "application/vnd.google-apps.document", "root",
                    anchor.plusSeconds(i * 10L).toEpochMilli(), 1, "body " + i);
        }
        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int guard = 0;
        while (more && guard++ < 10) {
            GrabResult page = connector.grab(req(kn, root, CursorDirection.FORWARD, pos, 2));
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("d1", "d2", "d3", "d4", "d5"), collected, "ascending, once each, no gaps");
    }

    // ---- backward: descending backfill --------------------------------------------------------

    @Test
    void backwardPagesHistoryBeforeAnchorThenExhausts(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.nativeDoc("h1", "H1", "application/vnd.google-apps.document", "root", anchor.minusSeconds(10).toEpochMilli(), 1, "b1");
        api.nativeDoc("h2", "H2", "application/vnd.google-apps.document", "root", anchor.minusSeconds(20).toEpochMilli(), 1, "b2");
        api.nativeDoc("h3", "H3", "application/vnd.google-apps.document", "root", anchor.minusSeconds(30).toEpochMilli(), 1, "b3");
        api.nativeDoc("future", "F", "application/vnd.google-apps.document", "root", anchor.plusSeconds(30).toEpochMilli(), 1, "bf");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int pages = 0;
        while (more && pages < 10) {
            GrabResult page = connector.grab(req(kn, root, CursorDirection.BACKWARD, pos, 2));
            pages++;
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("h1", "h2", "h3"), collected, "newest-of-old first, excludes post-anchor");
        assertEquals(2, pages, "3 items at cap=2 => 2 pages");
    }

    // ---- content mapping ----------------------------------------------------------------------

    @Test
    void nativeDocExportsToInlineTextPage(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.nativeDoc("d1", "Design Doc", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(5).toEpochMilli(), 4, "The design body text.");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem item = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        assertEquals(EntityType.PAGE, item.entityType());
        assertEquals("Design Doc", item.title());
        assertEquals("The design body text.", item.text());
        assertNull(item.fileRef(), "native docs carry text inline, no file ref");
        assertTrue(item.checksum().startsWith("drive:d1;v:4"));
    }

    @Test
    void binaryFileIsDownloadedToScratchAndReferencedByFileRef(@TempDir Path scratch) throws Exception {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] pdf = "%PDF-1.4 fake bytes".getBytes();
        api.binary("b1", "report.pdf", "application/pdf", "root", anchor.plusSeconds(5).toEpochMilli(), 7, pdf);

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem item = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        assertEquals(EntityType.FILE, item.entityType());
        assertEquals("application/pdf", item.contentType());
        assertNull(item.text(), "binary files are referenced, not inlined");
        assertNotNull(item.fileRef());
        Path staged = Path.of(item.fileRef());
        assertTrue(Files.exists(staged), "bytes staged to the scratch dir for Tika");
        assertTrue(staged.startsWith(scratch));
        assertEquals("%PDF-1.4 fake bytes", Files.readString(staged));
    }
}
