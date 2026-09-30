package io.personalassistant.ingestion.connector.ats.workday;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
        return new WorkdayPlatform(api).fetch(SITE, BoardFilter.ofLocations(hints));
    }

    @Test
    void theHintIsSentAsAQuerySoTheMultiSiteRoleIsFoundRatherThanDropped() {
        FakeWorkdayApi api = board();

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(List.of("R1", "R3"),
                items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(2, api.detailCalls.size(),
                "the query is what makes a large site affordable: only matches cost a detail call");
    }

    @Test
    void eachTermIsItsOwnQueryAndTheResultsAreUnioned() {
        FakeWorkdayApi api = new FakeWorkdayApi()
                .withPosting("R1", "Backend Engineer", "Bengaluru", "Bengaluru", "India", "<p>A.</p>")
                .withPosting("R2", "Data Engineer", "Bangalore", "Bangalore", "India", "<p>B.</p>");

        List<RawItem> items = fetch(api, List.of("bengaluru", "bangalore"));

        Assertions.assertEquals(List.of("bengaluru", "bangalore"), api.searchTexts);
        Assertions.assertEquals(List.of("R1", "R2"),
                items.stream().map(RawItem::externalId).toList());
    }

    @Test
    void aPostingMatchedByTwoTermsIsFetchedOnce() {
        FakeWorkdayApi api = new FakeWorkdayApi()
                .withPosting("R1", "Backend Engineer", "Bengaluru", "Bengaluru", "India", "<p>A.</p>");

        List<RawItem> items = fetch(api, List.of("bengaluru", "india"));

        Assertions.assertEquals(1, items.size());
        Assertions.assertEquals(1, api.detailCalls.size(), "matched twice, fetched once");
    }

    @Test
    void noHintsStillWalksTheWholeSite() {
        FakeWorkdayApi api = board();

        Assertions.assertEquals(3, fetch(api, List.of()).size());
        Assertions.assertEquals(List.of(""), api.searchTexts, "one blank query, as before");
    }

    @Test
    void theFullLocationIsWhatTheConnectorLaterFiltersOn() {
        RawItem multiSite = fetch(board(), List.of("india")).get(1);

        Assertions.assertEquals("Hyderabad, India", multiSite.metadata().get("location"));
    }

    @Test
    void theCountryIsAppendedToTheLocationSoACountryFilterCanMatch() {
        RawItem item = fetch(board(), List.of("india")).get(0);

        Assertions.assertEquals("Bengaluru, India", item.metadata().get("location"));
    }

    @Test
    void pagesThroughASiteLargerThanOnePage() {
        FakeWorkdayApi api = new FakeWorkdayApi();
        for (int i = 0; i < 55; i++) {
            api.withPosting("R" + i, "Engineer " + i, "Pune", "Pune", "India", "<p>Work.</p>");
        }

        Assertions.assertEquals(55, new WorkdayPlatform(api).fetch(SITE, BoardFilter.NONE).size());
    }

    @Test
    void aLabelNamesTheCompanyInsteadOfTheTenant() {
        RawItem item = new WorkdayPlatform(board())
                .fetch(SITE, "Acme Corp", BoardFilter.ofLocations(List.of("india"))).get(0);

        Assertions.assertEquals("Acme Corp", item.metadata().get("company"));
        Assertions.assertEquals("acme-corp|backend-engineer|bengaluru-india", item.metadata().get("dedupeKey"));
        Assertions.assertNotEquals(fetch(board(), List.of("india")).get(0).checksum(), item.checksum());
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
        String before = new WorkdayPlatform(new FakeWorkdayApi()
                .withPosting("R1", "Engineer", "Pune", "Pune", "India", "<p>Original.</p>"))
                .fetch(SITE, BoardFilter.NONE).get(0).checksum();
        String after = new WorkdayPlatform(new FakeWorkdayApi()
                .withPosting("R1", "Engineer", "Pune", "Pune", "India", "<p>Original. Plus Kafka.</p>"))
                .fetch(SITE, BoardFilter.NONE).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void aBareCompanyNameResolvesToFalseWithoutAnyRequest() {
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
                () -> new WorkdayPlatform(board()).fetch("paytm", BoardFilter.ofLocations(List.of()))).getMessage();

        Assertions.assertTrue(message.contains("tenant/site/wd"), message);
    }
}
