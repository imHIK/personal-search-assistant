package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The platform that pays per posting. Most of what matters here is about <em>not</em> making calls:
 * its listing has no description, so every posting that survives the location filter costs a second
 * request.
 */
class SmartRecruitersPlatformTest {

    private static FakeSmartRecruitersApi board() {
        return new FakeSmartRecruitersApi()
                .withPosting("Acme", "1", "Senior Backend Engineer", "Bengaluru, KA, India",
                        "<p>Build distributed systems in Go.</p>")
                .withPosting("Acme", "2", "Account Manager", "San Mateo, CA, United States",
                        "<p>Sell things.</p>")
                .withPosting("Acme", "3", "Staff Engineer", "Chennai, , India",
                        "<p>Own the platform.</p>");
    }

    private static List<RawItem> fetch(FakeSmartRecruitersApi api, List<String> hints) {
        return new SmartRecruitersPlatform(api).fetch("Acme", hints);
    }

    @Test
    void theLocationHintIsAppliedBeforeTheDetailCalls() {
        // The whole reason the hint exists. Freshworks is 157 postings of which 34 are in India;
        // filtering afterwards would still cost 157 detail requests every poll.
        FakeSmartRecruitersApi api = board();

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(2, items.size());
        Assertions.assertEquals(List.of("1", "3"), api.detailCalls,
                "the US posting must never be fetched in full");
    }

    @Test
    void noHintFetchesEverything() {
        FakeSmartRecruitersApi api = board();

        Assertions.assertEquals(3, fetch(api, List.of()).size());
        Assertions.assertEquals(3, api.detailCalls.size());
    }

    @Test
    void aFailedDetailFetchSkipsThatPostingRatherThanTheBoard() {
        // One posting withdrawn between the listing and the fetch must not cost the others.
        FakeSmartRecruitersApi api = board().failDetail("Acme", "1", new AtsApiException(404, "gone"));

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(List.of("3"), items.stream().map(RawItem::externalId).toList());
    }

    @Test
    void mapsAPostingIntoNormalisedMetadata() {
        RawItem item = fetch(board(), List.of("india")).get(0);

        Assertions.assertEquals("Senior Backend Engineer", item.title());
        Assertions.assertEquals("Bengaluru, KA, India", item.metadata().get("location"));
        Assertions.assertEquals("Acme", item.metadata().get("company"));
        Assertions.assertEquals("smartrecruiters", item.metadata().get("platform"));
        Assertions.assertEquals("SENIOR", item.metadata().get("seniority"));
        Assertions.assertEquals("acme|senior-backend-engineer|bengaluru-ka-india",
                item.metadata().get("dedupeKey"));
        Assertions.assertTrue(item.text().contains("distributed systems"));
    }

    @Test
    void theChecksumHashesTheBodyBecauseNoUpdateStampIsPublished() {
        // releasedDate is when the posting first went live and does not move on an edit, so without
        // hashing the body an edited posting would be skipped forever by change detection.
        String before = fetch(new FakeSmartRecruitersApi()
                .withPosting("Acme", "1", "Engineer", "Pune, India", "<p>Original.</p>"),
                List.of()).get(0).checksum();
        String after = fetch(new FakeSmartRecruitersApi()
                .withPosting("Acme", "1", "Engineer", "Pune, India", "<p>Original. Now with Kafka.</p>"),
                List.of()).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void pagesThroughABoardLargerThanOnePage() {
        FakeSmartRecruitersApi api = new FakeSmartRecruitersApi();
        for (int i = 0; i < 250; i++) {
            api.withPosting("Big", "id-" + i, "Engineer " + i, "Bengaluru, India", "<p>Work.</p>");
        }

        Assertions.assertEquals(250, new SmartRecruitersPlatform(api).fetch("Big", List.of()).size());
    }

    // ---- resolution ----------------------------------------------------------------------------

    @Test
    void anUnknownCompanyIsNotResolvedEvenThoughTheApiReturns200() {
        // SmartRecruiters answers an unknown company with 200 and totalFound: 0, so reading the status
        // code would resolve every company ever typed to this platform.
        Assertions.assertFalse(new SmartRecruitersPlatform(board()).hasBoard("NotACompany"));
    }

    @Test
    void aKnownCompanyResolves() {
        Assertions.assertTrue(new SmartRecruitersPlatform(board()).hasBoard("Acme"));
    }

    @Test
    void anOutageResolvesToFalseRatherThanPropagating() {
        // Resolution probes every platform in turn; a miss is the normal outcome for all but one.
        SmartRecruitersApi failing = new SmartRecruitersApi() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode listPostings(String c, int l, int o) {
                throw new AtsApiException(503, "down");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode posting(String c, String id) {
                throw new AtsApiException(503, "down");
            }
        };

        Assertions.assertFalse(new SmartRecruitersPlatform(failing).hasBoard("Acme"));
    }
}
