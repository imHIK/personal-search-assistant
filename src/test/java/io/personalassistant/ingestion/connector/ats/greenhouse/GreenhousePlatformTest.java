package io.personalassistant.ingestion.connector.ats.greenhouse;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GreenhousePlatformTest {

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
    private final GreenhousePlatform platform = new GreenhousePlatform(api);

    private List<RawItem> grab() {
        return platform.fetch("acme", BoardFilter.ofLocations(List.of()));
    }





    @Test
    void mapsAPostingIntoNormalisedMetadata() {
        RawItem senior = grab().get(0);

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
    void theChecksumIgnoresUpdatedAtBecauseGreenhouseMovesItInBulk() {
        // Measured live: 178 of GitLab's 227 postings share one updated_at to the second, and 233 of
        // Okta's 313 do. Trusting it re-embedded three quarters of a board for a change that never
        // touched the text — expensive everywhere, and fatal against a per-chunk embedding quota.
        String before = grab().get(0).checksum();

        String bumped = BOARD.replace("\"updated_at\":\"2026-08-01T10:00:00Z\"",
                "\"updated_at\":\"2026-09-04T18:00:00Z\"");
        String after = new GreenhousePlatform(new FakeGreenhouseApi().withBoard("acme", bumped))
                .fetch("acme", BoardFilter.NONE).get(0).checksum();

        Assertions.assertEquals(before, after, "a bulk touch must not re-index the posting");
    }

    @Test
    void theChecksumStillMovesWhenSomethingIndexedChanges() {
        // Invariant 3: the checksum must move whenever the posting does, or the edit is skipped forever.
        String before = grab().get(0).checksum();

        for (String edit : List.of(
                BOARD.replace("Build things.", "Build things with Kafka."),   // body
                BOARD.replace("Senior Backend Engineer", "Staff Backend Engineer"), // title
                BOARD.replace("Remote - US", "Bengaluru, India"))) {          // location
            String after = new GreenhousePlatform(new FakeGreenhouseApi().withBoard("acme", edit))
                    .fetch("acme", BoardFilter.NONE).get(0).checksum();
            Assertions.assertNotEquals(before, after, edit);
        }
    }

    @Test
    void extractsAnExplicitSalaryRangeButNotAYearsOfExperienceRange() {
        List<RawItem> items = grab();

        Assertions.assertEquals(150_000L, items.get(0).metadata().get("compMin"));
        Assertions.assertEquals(190_000L, items.get(0).metadata().get("compMax"));
        Assertions.assertNull(items.get(1).metadata().get("compMin"),
                "'2 - 5 years experience' must not be read as compensation");
    }

    @Test
    void greenhouseStatesNoCloseDateSoEntityExpiryIsLeftToTheKnowledgeWindow() {
        Assertions.assertNull(grab().get(0).expiresAt());
    }


}
