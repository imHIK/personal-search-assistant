package io.personalassistant.ingestion.connector.ats.greenhouse;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.SnapshotBoardConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GreenhouseConnectorTest {

    private static final String BOARD = """
            {"jobs":[
              {"id":101,"title":"Senior Backend Engineer","updated_at":"2026-08-01T10:00:00Z",
               "first_published":"2026-07-20T09:00:00Z","absolute_url":"https://boards.greenhouse.io/acme/jobs/101",
               "company_name":"Acme","location":{"name":"Remote - US"},
               "content":"<p>Build things. Salary $150,000 - $190,000.</p>"},
              {"id":102,"title":"Office Manager","updated_at":"2026-08-02T10:00:00Z",
               "absolute_url":"https://boards.greenhouse.io/acme/jobs/102",
               "company_name":"Acme","location":{"name":"New York, NY"},
               "content":"<p>Run the office. 2 - 5 years experience.</p>"}
            ]}""";

    private final FakeGreenhouseApi api = new FakeGreenhouseApi().withBoard("acme", BOARD);
    private final GreenhouseConnector connector = new GreenhouseConnector(api);

    private Knowledge knowledge() {
        return TestData.knowledge("kn_1", SourceType.GREENHOUSE, Instant.now(),
                Map.of(SnapshotBoardConnector.BOARDS_INPUT, List.of("acme")));
    }

    private GrabResult grab() {
        return connector.grab(new GrabContext(knowledge(), "acme",
                Map.of(SnapshotBoardConnector.BOARD_ATTRIBUTE, "acme"),
                CursorPosition.start(), TimeWindow.atOrAfter(Instant.EPOCH), 100));
    }

    @Test
    void isForwardOnlyBecauseAJobBoardHasNoHistoryWorthWalking() {
        Assertions.assertEquals(java.util.EnumSet.of(CursorDirection.FORWARD),
                connector.supportedDirections());
    }

    @Test
    void optsIntoARetentionWindowLongerThanItsPollInterval() {
        // A window shorter than the cadence would delete postings the very next poll re-creates.
        Duration retention = connector.defaultRetention().orElseThrow();
        Assertions.assertTrue(retention.compareTo(connector.defaultSchedule().interval()) > 0,
                "retention must exceed the poll interval or every board churns re-embeddings");
    }

    @Test
    void discoversOneIterablePerBoard() {
        List<SourceIterable> iterables = connector.discover(knowledge());

        Assertions.assertEquals(1, iterables.size());
        Assertions.assertEquals("acme", iterables.get(0).iterableId());
        Assertions.assertEquals("acme", iterables.get(0).attributes().get("board"));
    }

    @Test
    void grabReturnsTheWholeBoardAsASinglePage() {
        GrabResult page = grab();

        Assertions.assertEquals(2, page.items().size());
        Assertions.assertFalse(page.hasMore(), "a snapshot is complete; there is never a second page");
    }

    @Test
    void mapsAPostingIntoNormalisedMetadata() {
        RawItem senior = grab().items().get(0);

        Assertions.assertEquals("101", senior.externalId());
        Assertions.assertEquals(EntityType.JOB_POSTING, senior.entityType());
        Assertions.assertEquals("text/html", senior.contentType());
        Assertions.assertEquals("Acme", senior.metadata().get("company"));
        Assertions.assertEquals("SENIOR", senior.metadata().get("seniority"));
        Assertions.assertEquals(Boolean.TRUE, senior.metadata().get("remote"));
        Assertions.assertEquals("acme|senior-backend-engineer|remote-us",
                senior.metadata().get("dedupeKey"));
        Assertions.assertNull(senior.fileRef(), "a description is small enough to carry inline");
    }

    @Test
    void checksumTracksTheBoardsUpdateStamp() {
        // Invariant 3: the checksum must move whenever the posting does, or change detection skips it.
        RawItem item = grab().items().get(0);
        Assertions.assertEquals("gh:101;upd:2026-08-01T10:00:00Z", item.checksum());
    }

    @Test
    void extractsAnExplicitSalaryRangeButNotAYearsOfExperienceRange() {
        List<RawItem> items = grab().items();

        Assertions.assertEquals(150_000L, items.get(0).metadata().get("compMin"));
        Assertions.assertEquals(190_000L, items.get(0).metadata().get("compMax"));
        Assertions.assertNull(items.get(1).metadata().get("compMin"),
                "'2 - 5 years experience' must not be read as compensation");
    }

    @Test
    void greenhouseStatesNoCloseDateSoEntityExpiryIsLeftToTheKnowledgeWindow() {
        Assertions.assertNull(grab().items().get(0).expiresAt());
    }

    @Test
    void verifyRejectsABoardThatDoesNotExist() {
        Knowledge missing = TestData.knowledge("kn_2", SourceType.GREENHOUSE, Instant.now(),
                Map.of(SnapshotBoardConnector.BOARDS_INPUT, List.of("nope")));

        Assertions.assertThrows(AtsApiException.class, () -> connector.verify(missing));
    }

    @Test
    void verifyRejectsAKnowledgeWithNoBoards() {
        Knowledge empty = TestData.knowledge("kn_3", SourceType.GREENHOUSE, Instant.now(), Map.of());

        Assertions.assertThrows(IllegalArgumentException.class, () -> connector.verify(empty));
    }
}
