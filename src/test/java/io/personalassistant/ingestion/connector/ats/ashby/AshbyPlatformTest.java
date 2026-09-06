package io.personalassistant.ingestion.connector.ats.ashby;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AshbyPlatformTest {

    private static final String BOARD = """
            {"jobs":[
              {"id":"job-1","title":"Software Engineer",
               "publishedAt":"2026-08-01T00:00:00Z","location":"Anywhere","isRemote":true,
               "applyUrl":"https://jobs.ashbyhq.com/acme/job-1","team":"Core",
               "descriptionHtml":"<p>Ship product. Pay is discussed later.</p>",
               "compensation":{"summaryComponents":[
                  {"compensationType":"Salary","minValue":160000,"maxValue":200000,"currencyCode":"USD"}]}},
              {"id":"job-2","title":"Recruiter","publishedAt":"2026-08-02T00:00:00Z",
               "location":"London","isRemote":false,"closedAt":"2026-09-01T00:00:00Z",
               "applyUrl":"https://jobs.ashbyhq.com/acme/job-2",
               "descriptionPlain":"Hire people."}
            ]}""";

    private final AshbyPlatform platform =
            new AshbyPlatform(new FakeAshbyApi().withBoard("acme", BOARD));

    private List<RawItem> grab() {
        return platform.fetch("acme", BoardFilter.ofLocations(List.of()));
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

    @Test
    void theChecksumMovesWhenSomethingIndexedChanges() {
        // This was broken and invisible: Ashby's posting API has no updatedAt — the field this read
        // does not exist — so the checksum was a constant and an edited posting was never re-indexed,
        // violating invariant 3. The fixture used to invent the field, which is why nothing caught it.
        String before = grab().get(0).checksum();

        for (String edit : List.of(
                BOARD.replace("Ship product.", "Ship product with Kafka."),
                BOARD.replace("Software Engineer", "Staff Software Engineer"),
                BOARD.replace("\"location\":\"Anywhere\"", "\"location\":\"Bengaluru, India\""))) {
            String after = new AshbyPlatform(new FakeAshbyApi().withBoard("acme", edit))
                    .fetch("acme", BoardFilter.NONE).get(0).checksum();
            Assertions.assertNotEquals(before, after, edit);
        }
    }

    @Test
    void publishedAtIsCarriedAsTheItemTimestamp() {
        // It is the only date Ashby publishes, and the age filter falls back to it.
        Assertions.assertEquals(Instant.parse("2026-08-01T00:00:00Z"), grab().get(0).modifiedAt());
    }
}
