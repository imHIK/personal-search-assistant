package io.personalassistant.ingestion.connector.ats.rippling;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RipplingPlatformTest {

    private static FakeRipplingApi board() {
        return new FakeRipplingApi()
                .withJob("acme", "u1", "Senior Backend Engineer", "<p>Build distributed systems.</p>",
                        "Bengaluru, India")
                .withJob("acme", "u2", "Account Executive", "<p>Sell things.</p>", "Chicago, IL")
                .withJob("acme", "u3", "Staff Engineer", "<p>Own the platform.</p>",
                        "San Francisco, CA", "Bangalore, India");
    }

    private static List<RawItem> fetch(FakeRipplingApi api, List<String> locations) {
        return new RipplingPlatform(api).fetch("acme", BoardFilter.ofLocations(locations));
    }

    @Test
    void theLocationHintIsAppliedBeforeTheDetailCalls() {
        FakeRipplingApi api = board();

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(List.of("u1", "u3"), items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(List.of("u1", "u3"), api.detailCalls,
                "the Chicago job must never be fetched in full");
    }

    @Test
    void aJobListedOncePerLocationIsOneItemAndOneDetailCall() {
        FakeRipplingApi api = board();

        List<RawItem> items = fetch(api, List.of());

        Assertions.assertEquals(3, items.size());
        Assertions.assertEquals(3, api.detailCalls.size());
        Assertions.assertEquals(3, new RipplingPlatform(board()).countPostings("acme").orElseThrow());
    }

    @Test
    void aFailedDetailFetchSkipsThatJobRatherThanTheBoard() {
        FakeRipplingApi api = board().failDetail("acme", "u1", new AtsApiException(404, "gone"));

        Assertions.assertEquals(List.of("u3"), fetch(api, List.of("india")).stream()
                .map(RawItem::externalId).toList());
    }

    @Test
    void mapsAJobIntoNormalisedMetadata() {
        RawItem item = fetch(board(), List.of()).get(2);

        Assertions.assertEquals("Staff Engineer", item.title());
        Assertions.assertEquals("San Francisco, CA; Bangalore, India", item.metadata().get("location"));
        Assertions.assertEquals("https://ats.rippling.com/acme/jobs/u3", item.uri());
        Assertions.assertEquals("acme", item.metadata().get("company"));
        Assertions.assertEquals("rippling", item.metadata().get("platform"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals("STAFF", item.metadata().get("seniority"));
        Assertions.assertEquals(Instant.parse("2026-09-25T18:41:16.881Z"), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().startsWith("<p>Own the platform."), "the role leads the body");
    }

    @Test
    void theChecksumMovesWhenTheBodyChanges() {
        String before = fetch(new FakeRipplingApi()
                .withJob("acme", "u1", "Engineer", "<p>Original.</p>", "Pune, India"), List.of())
                .get(0).checksum();
        String after = fetch(new FakeRipplingApi()
                .withJob("acme", "u1", "Engineer", "<p>Original, with Kafka.</p>", "Pune, India"), List.of())
                .get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void anUnknownBoardIsAMissRatherThanAThrow() {
        Assertions.assertFalse(new RipplingPlatform(board()).hasBoard("nosuchco"));
    }
}
