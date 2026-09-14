package io.personalassistant.ingestion.connector.google.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
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
    private final GoogleAccessTokens tokens = conn -> GoogleAuth.unlimited("test-token");
    private final Connection connection = TestData.connection("conn_drive", SourceType.GOOGLE_DRIVE, true,
            Map.of("accessToken", "test-token"));
    private final ConnectionResolver connections = kn -> connection;
    private GoogleDriveConnector connector;

    private GoogleDriveConnector connector(Path scratch) {
        GoogleDriveConnector c = new GoogleDriveConnector(api, tokens, connections);
        c.downloadDir = Optional.of(scratch.toString());
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

    @Test
    void aConfiguredRootIsNamedRatherThanShownAsItsId(@TempDir Path scratch) {
        // Sub-folders arrive from a listing that carries names; a configured root is a bare id the
        // user pasted in. Labelling it with itself put a raw Drive id in front of the user as the
        // folder's name, on the source overview and over its sync history.
        connector = connector(scratch);
        api.folder("1qTKf3MTYH6BTMq9l60", "Job hunt", "root");

        List<SourceIterable> iterables =
                connector.discover(knowledge(Instant.now(), Map.of("folderIds", List.of("1qTKf3MTYH6BTMq9l60"))));

        assertEquals("Job hunt", iterable(iterables, "1qTKf3MTYH6BTMq9l60").displayName());
    }

    @Test
    void aRootWhoseNameCannotBeReadFallsBackToItsId(@TempDir Path scratch) {
        // A label is not worth failing discovery over; the walk itself will surface a bad root.
        connector = connector(scratch);

        List<SourceIterable> iterables =
                connector.discover(knowledge(Instant.now(), Map.of("folderIds", List.of("gone"))));

        assertEquals("gone", iterable(iterables, "gone").displayName());
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
    void nativeDocIsMappedForExportWithoutExportingDuringTheWalk(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.nativeDoc("d1", "Design Doc", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(5).toEpochMilli(), 4, "The design body text.");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem item = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        assertEquals(EntityType.PAGE, item.entityType());
        assertEquals("Design Doc", item.title());
        assertEquals("text/plain", item.contentType(), "the export mime is resolved during the walk");
        assertTrue(item.checksum().startsWith("drive:d1;v:4"));
        assertNull(item.text(), "the export is deferred to materialize");
        assertNull(item.fileRef(), "native docs carry text inline, no file ref");
        assertEquals(0, api.exports, "the walk must not export");
    }

    @Test
    void materializeExportsANativeDocToInlineText(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.nativeDoc("d1", "Design Doc", "application/vnd.google-apps.document", "root",
                anchor.plusSeconds(5).toEpochMilli(), 4, "The design body text.");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem item = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        Entity.Content content = connector.materialize(kn, item);

        assertEquals("The design body text.", content.text());
        assertNull(content.fileRef());
        assertEquals(1, api.exports);
    }

    @Test
    void binaryFileIsMappedToItsStagedPathWithoutDownloading(@TempDir Path scratch) {
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
        assertNotNull(item.fileRef(), "the path the bytes will occupy is known without fetching them");
        Path staged = Path.of(item.fileRef());
        assertTrue(staged.startsWith(scratch));
        assertFalse(Files.exists(staged), "nothing is written until materialize");
        assertEquals(0, api.downloads, "the walk must not download");
    }

    @Test
    void materializeDownloadsToTheVeryPathTheWalkReported(@TempDir Path scratch) throws Exception {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] pdf = "%PDF-1.4 fake bytes".getBytes();
        api.binary("b1", "report.pdf", "application/pdf", "root", anchor.plusSeconds(5).toEpochMilli(), 7, pdf);

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem item = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        Entity.Content content = connector.materialize(kn, item);

        assertEquals(item.fileRef(), content.fileRef(), "what is stored and what is written must agree");
        Path staged = Path.of(content.fileRef());
        assertTrue(Files.exists(staged), "bytes staged to the scratch dir for Tika");
        assertEquals("%PDF-1.4 fake bytes", Files.readString(staged));
        assertEquals(1, api.downloads);
    }

    @Test
    void aWalkOverManyItemsTransfersNoBytesAtAll(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 4; i++) {
            api.binary("b" + i, "file" + i + ".pdf", "application/pdf", "root",
                    anchor.plusSeconds(i + 1).toEpochMilli(), 1, ("bytes " + i).getBytes());
            api.nativeDoc("d" + i, "Doc " + i, "application/vnd.google-apps.document", "root",
                    anchor.plusSeconds(i + 1).toEpochMilli(), 1, "text " + i);
        }

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        GrabResult page = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 50));

        assertEquals(8, page.items().size());
        assertEquals(0, api.downloads);
        assertEquals(0, api.exports);
    }

    @Test
    void theTwoSkipsStillHappenDuringTheWalkAndFetchNothing(@TempDir Path scratch) {
        connector = connector(scratch);
        connector.maxFileBytes = 8;
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.binary("big", "huge.pdf", "application/pdf", "root",
                anchor.plusSeconds(1).toEpochMilli(), 1, "well over eight bytes".getBytes());
        // A native type with no export mime — a Drive form has nothing textual to index.
        api.nativeDoc("form", "Signup", "application/vnd.google-apps.form", "root",
                anchor.plusSeconds(2).toEpochMilli(), 1, "unused");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        GrabResult page = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10));

        assertTrue(page.items().isEmpty(), "oversized files and unsupported native types are skipped");
        assertEquals(0, api.downloads);
        assertEquals(0, api.exports);
    }

    // ---- L11: per-item re-list --------------------------------------------------------------

    @Test
    void declaresThatItsContentMustBeFetchedAgainBeforeReIndexing() {
        // The whole of L11 hangs off this: its fileRef is a copy in a dir the OS may empty.
        assertEquals(ReindexMode.FETCH_AND_REINDEX, connector(Path.of("/tmp")).defaultReindexMode());
    }

    @Test
    void fetchOneReListsAFileExactlyAsAWalkWould(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.binary("f1", "report.pdf", "application/pdf", "root",
                anchor.plusSeconds(1).toEpochMilli(), 3, "pdf-bytes".getBytes());
        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem walked = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10))
                .items().get(0);

        RawItem relisted = connector.fetchOne(kn, entityFor(walked)).orElseThrow();

        // Identical, field for field, is the requirement: a checksum that differed here would make
        // every subsequent poll see a change that never happened.
        assertEquals(walked.checksum(), relisted.checksum());
        assertEquals(walked.fileRef(), relisted.fileRef());
        assertEquals(walked.contentType(), relisted.contentType());
        assertEquals(walked.metadata(), relisted.metadata());
        assertEquals(0, api.downloads, "re-listing is metadata only; materialize pays for the bytes");
    }

    @Test
    void fetchOneRestagesTheBytesThroughMaterialize(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.binary("f2", "notes.pdf", "application/pdf", "root",
                anchor.plusSeconds(1).toEpochMilli(), 1, "fresh-bytes".getBytes());
        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem walked = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10))
                .items().get(0);
        connector.materialize(kn, walked);
        Path staged = Path.of(walked.fileRef());
        assertTrue(Files.exists(staged));

        // The OS empties the scratch dir; this is the state that used to dead-letter the entity.
        assertTrue(staged.toFile().delete());

        RawItem relisted = connector.fetchOne(kn, entityFor(walked)).orElseThrow();
        Entity.Content content = connector.materialize(kn, relisted);

        assertEquals(staged.toString(), content.fileRef(), "re-staged at the same deterministic path");
        assertTrue(Files.exists(staged), "and the bytes are actually back on disk");
    }

    @Test
    void fetchOneReportsATrashedFileAsGone(@TempDir Path scratch) {
        connector = connector(scratch);
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.binary("f3", "old.pdf", "application/pdf", "root",
                anchor.plusSeconds(1).toEpochMilli(), 1, "bytes".getBytes());
        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable root = iterable(connector.discover(kn), "root");
        RawItem walked = connector.grab(req(kn, root, CursorDirection.FORWARD, CursorPosition.start(), 10))
                .items().get(0);
        api.trash("f3");

        assertTrue(connector.fetchOne(kn, entityFor(walked)).isEmpty(),
                "the walk's query excludes trashed files, so the re-list must agree");
    }

    @Test
    void fetchOneReportsADeletedFileAsGone(@TempDir Path scratch) {
        connector = connector(scratch);
        Knowledge kn = knowledge(Instant.now(), Map.of());
        Entity orphan = TestData.ingestedFile("ent_x", kn.id(), "vanished",
                scratch.resolve("vanished.pdf").toString(), "application/pdf");

        assertTrue(connector.fetchOne(kn, orphan).isEmpty(), "a 404 is an answer here, not a fault");
    }

    /** The entity the walk would have produced for this item — all fetchOne reads is externalId. */
    private static Entity entityFor(RawItem item) {
        return TestData.ingestedFile("ent_" + item.externalId(), "kn_drive", item.externalId(),
                item.fileRef(), item.contentType());
    }
}
