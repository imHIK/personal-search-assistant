package io.personalassistant.ingestion.connector.ats.lever;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LeverPlatformTest {

    private static String site(String description) {
        return """
            [{"id":"abc-123","text":"Staff Platform Engineer","createdAt":1753000000000,
              "hostedUrl":"https://jobs.lever.co/acme/abc-123",
              "categories":{"location":"Berlin","team":"Platform","commitment":"Full-time"},
              "description":"%s"}]""".formatted(description);
    }

    private RawItem grabOne(String description) {
        return new LeverPlatform(new FakeLeverApi().withSite("acme", site(description)))
                .fetch("acme", BoardFilter.ofLocations(List.of())).get(0);
    }

    @Test
    void mapsEpochMillisDatesAndCategoryFields() {
        RawItem item = grabOne("<p>Own the platform.</p>");

        Assertions.assertEquals("abc-123", item.externalId());
        Assertions.assertEquals("Staff Platform Engineer", item.title());
        Assertions.assertEquals("Berlin", item.metadata().get("location"));
        Assertions.assertEquals("Platform", item.metadata().get("team"));
        Assertions.assertEquals("STAFF", item.metadata().get("seniority"));
        Assertions.assertEquals(Instant.ofEpochMilli(1753000000000L), item.metadata().get("postedAt"));
    }

    @Test
    void checksumChangesWhenTheBodyChangesBecauseLeverPublishesNoUpdateStamp() {
        // Lever exposes only createdAt, which never moves. Without hashing the body an edited
        // posting would keep its checksum forever and change detection would skip it permanently —
        // a direct invariant-3 violation.
        String before = grabOne("<p>Own the platform.</p>").checksum();
        String after = grabOne("<p>Own the platform. Now with Kubernetes.</p>").checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void checksumIsStableForAnUnchangedPosting() {
        Assertions.assertEquals(grabOne("<p>Same.</p>").checksum(), grabOne("<p>Same.</p>").checksum());
    }
}
