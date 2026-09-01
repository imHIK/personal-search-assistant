package io.personalassistant.ingestion.connector.ats.ashby;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.ats.SnapshotBoardConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AshbyConnectorTest {

    private static final String BOARD = """
            {"jobs":[
              {"id":"job-1","title":"Software Engineer","updatedAt":"2026-08-10T00:00:00Z",
               "publishedAt":"2026-08-01T00:00:00Z","location":"Anywhere","isRemote":true,
               "applyUrl":"https://jobs.ashbyhq.com/acme/job-1","team":"Core",
               "descriptionHtml":"<p>Ship product. Pay is discussed later.</p>",
               "compensation":{"summaryComponents":[
                  {"compensationType":"Salary","minValue":160000,"maxValue":200000,"currencyCode":"USD"}]}},
              {"id":"job-2","title":"Recruiter","updatedAt":"2026-08-11T00:00:00Z",
               "location":"London","isRemote":false,"closedAt":"2026-09-01T00:00:00Z",
               "applyUrl":"https://jobs.ashbyhq.com/acme/job-2",
               "descriptionPlain":"Hire people."}
            ]}""";

    private final AshbyConnector connector =
            new AshbyConnector(new FakeAshbyApi().withBoard("acme", BOARD));

    private List<RawItem> grab() {
        Knowledge kn = TestData.knowledge("kn_1", SourceType.ASHBY, Instant.now(),
                Map.of(SnapshotBoardConnector.BOARDS_INPUT, List.of("acme")));
        return connector.grab(new GrabContext(kn, "acme",
                Map.of(SnapshotBoardConnector.BOARD_ATTRIBUTE, "acme"),
                CursorPosition.start(), TimeWindow.atOrAfter(Instant.EPOCH), 100)).items();
    }

    @Test
    void prefersStructuredCompensationOverScrapingTheDescription() {
        RawItem engineer = grab().get(0);

        Assertions.assertEquals(160_000L, engineer.metadata().get("compMin"));
        Assertions.assertEquals(200_000L, engineer.metadata().get("compMax"));
        Assertions.assertEquals("USD", engineer.metadata().get("compCurrency"));
    }

    @Test
    void prefersTheStatedRemoteFlagOverInferringItFromProse() {
        List<RawItem> items = grab();

        Assertions.assertEquals(Boolean.TRUE, items.get(0).metadata().get("remote"));
        Assertions.assertEquals(Boolean.FALSE, items.get(1).metadata().get("remote"),
                "a stated isRemote=false must win even though the location reads 'London'");
    }

    @Test
    void carriesAStatedCloseDateAsEntityExpiry() {
        // Ashby is the one board of the three that can state this; it beats the knowledge window.
        List<RawItem> items = grab();

        Assertions.assertNull(items.get(0).expiresAt());
        Assertions.assertEquals(Instant.parse("2026-09-01T00:00:00Z"), items.get(1).expiresAt());
    }

    @Test
    void fallsBackToPlainTextWhenNoHtmlDescriptionIsPublished() {
        RawItem recruiter = grab().get(1);

        Assertions.assertEquals("text/plain", recruiter.contentType());
        Assertions.assertEquals("Hire people.", recruiter.text());
    }

    @Test
    void leavesSeniorityUnsetWhenTheTitleCarriesNoMarker() {
        // "Software Engineer" is genuinely ambiguous; guessing a band would corrupt seniority filters.
        Assertions.assertNull(grab().get(0).metadata().get("seniority"));
    }
}
