package io.personalassistant.ingestion.connector.google.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GmailConnectorTest {

    private final FakeGmailApi api = new FakeGmailApi();
    private final GoogleAccessTokens tokens = conn -> "test-token";
    private final Connection connection = TestData.connection("conn_gmail", SourceType.GMAIL, true,
            Map.of("accessToken", "test-token"));
    private final ConnectionResolver connections = kn -> connection;
    private final GmailConnector connector = new GmailConnector(api, tokens, connections);

    private Knowledge knowledge(Instant anchor, Map<String, Object> inputs) {
        return TestData.knowledge("kn_gmail", SourceType.GMAIL, anchor, inputs);
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

    // ---- discovery ---------------------------------------------------------------------------

    @Test
    void discoversSingleAllMailIterableByDefault() {
        List<SourceIterable> iterables = connector.discover(knowledge(Instant.now(), Map.of()));
        assertEquals(1, iterables.size());
        assertEquals(GmailConnector.ALL_MAIL_ITERABLE, iterables.get(0).iterableId());
    }

    @Test
    void discoversOneIterablePerConfiguredLabelWithNames() {
        api.label("Label_1", "Work").label("INBOX", "Inbox");
        Knowledge kn = knowledge(Instant.now(), Map.of("labelIds", List.of("Label_1", "INBOX")));

        List<SourceIterable> iterables = connector.discover(kn);
        assertEquals(2, iterables.size());
        assertEquals("Work", iterables.get(0).displayName());
        assertEquals("Label_1", iterables.get(0).attributes().get("labelId"));
    }

    // ---- forward: incremental high-water walk ------------------------------------------------

    @Test
    void forwardReturnsMailAtOrAfterAnchorAndAdvancesHighWater() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.add("m_new1", anchor.plusSeconds(10).toEpochMilli(), List.of("INBOX"), "New one", "a@x.com", "hello one");
        api.add("m_new2", anchor.plusSeconds(20).toEpochMilli(), List.of("INBOX"), "New two", "b@x.com", "hello two");
        api.add("m_old", anchor.minusSeconds(60).toEpochMilli(), List.of("INBOX"), "Old", "c@x.com", "old body");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable all = connector.discover(kn).get(0);

        GrabResult page = connector.grab(req(kn, all, CursorDirection.FORWARD, CursorPosition.start(), 10));
        assertEquals(List.of("m_new2", "m_new1"), ids(page), "newest-first, excludes pre-anchor mail");
        assertFalse(page.hasMore());

        // A newer mail arrives; re-grabbing from the returned (high-water) position picks it up.
        api.add("m_new3", anchor.plusSeconds(40).toEpochMilli(), List.of("INBOX"), "New three", "d@x.com", "hello three");
        GrabResult next = connector.grab(req(kn, all, CursorDirection.FORWARD, page.cursor(), 10));
        assertTrue(ids(next).contains("m_new3"), "high-water floor advanced so the new mail is returned");
    }

    @Test
    void forwardPagesThroughAllMatchesOnce() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 5; i++) {
            api.add("m" + i, anchor.plusSeconds(i * 10L).toEpochMilli(), List.of("INBOX"), "s" + i, "a@x.com", "b" + i);
        }
        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable all = connector.discover(kn).get(0);

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int guard = 0;
        while (more && guard++ < 10) {
            GrabResult page = connector.grab(req(kn, all, CursorDirection.FORWARD, pos, 2));
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("m5", "m4", "m3", "m2", "m1"), collected, "all matches, newest-first, once each");
    }

    // ---- backward: backfill sweep ------------------------------------------------------------

    @Test
    void backwardPagesHistoryBeforeAnchorThenExhausts() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.add("h1", anchor.minusSeconds(10).toEpochMilli(), List.of("INBOX"), "h1", "a@x.com", "b1");
        api.add("h2", anchor.minusSeconds(20).toEpochMilli(), List.of("INBOX"), "h2", "a@x.com", "b2");
        api.add("h3", anchor.minusSeconds(30).toEpochMilli(), List.of("INBOX"), "h3", "a@x.com", "b3");
        api.add("future", anchor.plusSeconds(30).toEpochMilli(), List.of("INBOX"), "f", "a@x.com", "bf");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable all = connector.discover(kn).get(0);

        List<String> collected = new ArrayList<>();
        CursorPosition pos = CursorPosition.start();
        boolean more = true;
        int pages = 0;
        while (more && pages < 10) {
            GrabResult page = connector.grab(req(kn, all, CursorDirection.BACKWARD, pos, 2));
            pages++;
            collected.addAll(ids(page));
            pos = page.cursor();
            more = page.hasMore();
        }
        assertEquals(List.of("h1", "h2", "h3"), collected, "newest-of-old first, excludes post-anchor mail");
        assertEquals(2, pages, "3 items at cap=2 => 2 pages");
    }

    // ---- mapping -----------------------------------------------------------------------------

    @Test
    void mapsMessageIntoEmailRawItemWithHeadersAndBody() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        api.add("m1", anchor.plusSeconds(5).toEpochMilli(), List.of("INBOX"), "Quarterly numbers", "cfo@x.com", "Revenue is up.");

        Knowledge kn = knowledge(anchor, Map.of());
        SourceIterable all = connector.discover(kn).get(0);
        RawItem item = connector.grab(req(kn, all, CursorDirection.FORWARD, CursorPosition.start(), 10)).items().get(0);

        assertEquals(EntityType.EMAIL, item.entityType());
        assertEquals("Quarterly numbers", item.title());
        assertNotNull(item.text());
        assertTrue(item.text().contains("Subject: Quarterly numbers"));
        assertTrue(item.text().contains("cfo@x.com"));
        assertTrue(item.text().contains("Revenue is up."));
        assertTrue(item.checksum().startsWith("gmail:m1"));
        assertTrue(item.uri().contains("m1"));
        assertFalse(item.deleted());
    }
}
