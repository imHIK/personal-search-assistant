package io.personalassistant.ingestion.connector.ats.greenhouse;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
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
        return platform.fetch("acme", List.of());
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
    void checksumTracksTheBoardsUpdateStamp() {
        // Invariant 3: the checksum must move whenever the posting does, or change detection skips it.
        RawItem item = grab().get(0);
        Assertions.assertEquals("gh:101;upd:2026-08-01T10:00:00Z", item.checksum());
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
