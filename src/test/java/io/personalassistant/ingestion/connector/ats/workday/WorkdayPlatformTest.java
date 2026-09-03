package io.personalassistant.ingestion.connector.ats.workday;

import io.personalassistant.domain.model.RawItem;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Paging, the location-count trap, and the per-posting cost controls. */
class WorkdayPlatformTest {

    private static final String SITE = "acme/site/wd5";

    private static FakeWorkdayApi board() {
        return new FakeWorkdayApi()
                .withPosting("R1", "Backend Engineer", "Bengaluru", "Bengaluru", "India",
                        "<p>Build services.</p>")
                .withPosting("R2", "Account Manager", "San Jose", "San Jose", "United States of America",
                        "<p>Sell things.</p>")
                .withPosting("R3", "Staff Engineer", "5 Locations", "Hyderabad", "India",
                        "<p>Own the platform.</p>");
    }

    private static List<RawItem> fetch(FakeWorkdayApi api, List<String> hints) {
        return new WorkdayPlatform(api).fetch(SITE, hints);
    }

    @Test
    void theHintIsIgnoredBecauseTheListingLocationIsNotTrustworthy() {
        // The trap this platform must not fall into. R1's listing says "Bengaluru" with no country, so
        // filtering it against "india" would drop a genuine Indian role; R3's says "5 Locations", a
        // count with no place at all. Both are fetched, and the connector filters the FULL locations.
        FakeWorkdayApi api = board();

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(List.of("R1", "R2", "R3"),
                items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(3, api.detailCalls.size(),
                "everything is fetched; narrowing happens downstream where the country is known");
    }

    @Test
    void theFullLocationIsWhatTheConnectorLaterFiltersOn() {
        // R3's listing said "5 Locations"; its detail says Hyderabad, India. That is what makes the
        // connector's authoritative filter able to keep it.
        RawItem multiSite = fetch(board(), List.of("india")).get(2);

        Assertions.assertEquals("Hyderabad, India", multiSite.metadata().get("location"));
    }

    @Test
    void theCountryIsAppendedToTheLocationSoACountryFilterCanMatch() {
        // Workday's location fields hold city names alone; without the country, a filter naming
        // "India" would never match a role in Bengaluru.
        RawItem item = fetch(board(), List.of("india")).get(0);

        Assertions.assertEquals("Bengaluru, India", item.metadata().get("location"));
    }

    @Test
    void pagesThroughASiteLargerThanOnePage() {
        // Workday caps a page at 20 against sites holding several hundred.
        FakeWorkdayApi api = new FakeWorkdayApi();
        for (int i = 0; i < 55; i++) {
            api.withPosting("R" + i, "Engineer " + i, "Pune", "Pune", "India", "<p>Work.</p>");
        }

        Assertions.assertEquals(55, new WorkdayPlatform(api).fetch(SITE, List.of()).size());
    }

    @Test
    void mapsAPostingIntoNormalisedMetadata() {
        RawItem item = fetch(board(), List.of("india")).get(0);

        Assertions.assertEquals("Backend Engineer", item.title());
        Assertions.assertEquals("acme", item.metadata().get("company"));
        Assertions.assertEquals("workday", item.metadata().get("platform"));
        Assertions.assertEquals("acme|backend-engineer|bengaluru-india", item.metadata().get("dedupeKey"));
        Assertions.assertTrue(item.text().contains("Build services"));
    }

    @Test
    void theChecksumHashesTheBodyBecauseNoUpdateStampIsPublished() {
        // postedOn is prose ("Posted Today") and startDate is the requisition date; neither moves on
        // an edit, so without hashing the body an edited posting is skipped forever.
        String before = new WorkdayPlatform(new FakeWorkdayApi()
                .withPosting("R1", "Engineer", "Pune", "Pune", "India", "<p>Original.</p>"))
                .fetch(SITE, List.of()).get(0).checksum();
        String after = new WorkdayPlatform(new FakeWorkdayApi()
                .withPosting("R1", "Engineer", "Pune", "Pune", "India", "<p>Original. Plus Kafka.</p>"))
                .fetch(SITE, List.of()).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    // ---- resolution ----------------------------------------------------------------------------

    @Test
    void aBareCompanyNameResolvesToFalseWithoutAnyRequest() {
        // Resolution probes every platform for every company; a speculative POST per name would make
        // adding companies slow for no possible benefit.
        FakeWorkdayApi api = board();

        Assertions.assertFalse(new WorkdayPlatform(api).hasBoard("paytm"));
        Assertions.assertTrue(api.detailCalls.isEmpty());
    }

    @Test
    void aValidTripleResolves() {
        Assertions.assertTrue(new WorkdayPlatform(board()).hasBoard(SITE));
    }

    @Test
    void fetchingAnUnparseableHandleIsRejectedClearly() {
        String message = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkdayPlatform(board()).fetch("paytm", List.of())).getMessage();

        Assertions.assertTrue(message.contains("tenant/site/wd"), message);
    }
}
