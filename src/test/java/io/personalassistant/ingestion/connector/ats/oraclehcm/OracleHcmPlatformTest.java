package io.personalassistant.ingestion.connector.ats.oraclehcm;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Handle parsing, the per-posting cost control, and mapping. */
class OracleHcmPlatformTest {

    private static final String SITE = "eofe.fa.us2.oraclecloud.com/BNY-Careers";

    private static FakeOracleHcmApi board() {
        return new FakeOracleHcmApi()
                .withRequisition("1", "Backend Engineer", "Pune, Maharashtra, India", "<p>Build.</p>")
                .withRequisition("2", "Fund Accountant", "Dublin, Co. Dublin, Ireland", "<p>Count.</p>")
                .withRequisition("3", "Data Engineer", "Bengaluru, Karnataka, India", "<p>Model.</p>");
    }

    private static List<RawItem> fetch(FakeOracleHcmApi api, List<String> hints) {
        return new OracleHcmPlatform(api).fetch(SITE, BoardFilter.ofLocations(hints));
    }

    // ---- cost control --------------------------------------------------------------------------

    @Test
    void theLocationTermsAreSentAsKeywordsSoOnlyMatchesCostADetailCall() {
        // The listing carries no description, so every kept requisition costs a second request. Without
        // the keyword prefilter, JPMorgan's 7,325 requisitions would be 7,326 requests per poll.
        FakeOracleHcmApi api = board();

        List<RawItem> items = fetch(api, List.of("india"));

        Assertions.assertEquals(List.of("1", "3"), items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(2, api.detailCalls.size(), "the Dublin role is never fetched");
    }

    @Test
    void eachTermIsItsOwnQueryAndTheResultsAreUnioned() {
        // Alternative spellings, not a conjunction — the same reason Workday sends one query per term.
        FakeOracleHcmApi api = new FakeOracleHcmApi()
                .withRequisition("1", "Backend Engineer", "Bengaluru, India", "<p>A.</p>")
                .withRequisition("2", "Data Engineer", "Bangalore, India", "<p>B.</p>");

        List<RawItem> items = fetch(api, List.of("bengaluru", "bangalore"));

        Assertions.assertEquals(List.of("bengaluru", "bangalore"), api.keywords);
        Assertions.assertEquals(List.of("1", "2"), items.stream().map(RawItem::externalId).toList());
    }

    @Test
    void aRequisitionMatchedByTwoTermsIsFetchedOnce() {
        FakeOracleHcmApi api = new FakeOracleHcmApi()
                .withRequisition("1", "Backend Engineer", "Pune, Maharashtra, India", "<p>A.</p>");

        Assertions.assertEquals(1, fetch(api, List.of("pune", "india")).size());
        Assertions.assertEquals(1, api.detailCalls.size(), "matched twice, fetched once");
    }

    @Test
    void noHintsWalksTheWholeSite() {
        FakeOracleHcmApi api = board();

        Assertions.assertEquals(3, fetch(api, List.of()).size());
        Assertions.assertEquals(List.of(""), api.keywords);
    }

    @Test
    void aWithdrawnRequisitionSkipsItselfRatherThanFailingTheSite() {
        // One posting pulled between the listing and the detail call must not cost the other two.
        FakeOracleHcmApi api = new FakeOracleHcmApi() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode requisition(OracleHcmSite site, String id) {
                if ("2".equals(id)) {
                    throw new io.personalassistant.ingestion.connector.ats.AtsApiException(404, "gone");
                }
                return super.requisition(site, id);
            }
        };
        api.withRequisition("1", "A", "Pune, India", "<p>A.</p>")
                .withRequisition("2", "B", "Pune, India", "<p>B.</p>")
                .withRequisition("3", "C", "Pune, India", "<p>C.</p>");

        Assertions.assertEquals(List.of("1", "3"),
                fetch(api, List.of()).stream().map(RawItem::externalId).toList());
    }

    @Test
    void pagesThroughASiteLargerThanOnePage() {
        FakeOracleHcmApi api = new FakeOracleHcmApi();
        for (int i = 0; i < 250; i++) {
            api.withRequisition("R" + i, "Engineer " + i, "Pune, India", "<p>Work.</p>");
        }

        Assertions.assertEquals(250, fetch(api, List.of()).size());
    }

    // ---- mapping -------------------------------------------------------------------------------

    @Test
    void mapsARequisitionIntoNormalisedMetadata() {
        RawItem item = fetch(board(), List.of("pune")).get(0);

        Assertions.assertEquals("Backend Engineer", item.title());
        Assertions.assertEquals("oraclehcm", item.metadata().get("platform"));
        // Oracle spells the country out, so nothing has to be appended for "India" to match.
        Assertions.assertEquals("Pune, Maharashtra, India", item.metadata().get("location"));
        Assertions.assertEquals("https://eofe.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/"
                + "sites/BNY-Careers/job/1", item.metadata().get("uri"));
        Assertions.assertTrue(item.text().contains("Build"));
    }

    @Test
    void theChecksumHashesTheBodyBecausePostedDateDoesNotMoveOnAnEdit() {
        String before = fetch(new FakeOracleHcmApi()
                .withRequisition("1", "Engineer", "Pune, India", "<p>Original.</p>"), List.of())
                .get(0).checksum();
        String after = fetch(new FakeOracleHcmApi()
                .withRequisition("1", "Engineer", "Pune, India", "<p>Original. Plus Kafka.</p>"), List.of())
                .get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    // ---- resolution ----------------------------------------------------------------------------

    @Test
    void aBareCompanyNameResolvesToFalseWithoutAnyRequest() {
        // Resolution probes every platform for every company; a speculative request per name would
        // make adding companies slow for no possible benefit.
        FakeOracleHcmApi api = board();

        Assertions.assertFalse(new OracleHcmPlatform(api).hasBoard("paytm"));
        Assertions.assertTrue(api.keywords.isEmpty());
    }

    @Test
    void aHostSitePairResolves() {
        Assertions.assertTrue(new OracleHcmPlatform(board()).hasBoard(SITE));
    }

    @Test
    void aPastedCareerSiteUrlResolves() {
        Assertions.assertTrue(new OracleHcmPlatform(board()).hasBoard(
                "https://eofe.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/BNY-Careers/jobs"));
    }

    @Test
    void anEmptySiteReadsAsNoBoard() {
        // Indistinguishable from a wrong site number, and reported the same way — the accepted
        // ambiguity every platform here shares.
        Assertions.assertFalse(new OracleHcmPlatform(new FakeOracleHcmApi()).hasBoard(SITE));
    }

    @Test
    void fetchingAnUnparseableHandleIsRejectedClearly() {
        String message = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OracleHcmPlatform(board()).fetch("paytm", BoardFilter.ofLocations(List.of()))).getMessage();

        Assertions.assertTrue(message.contains("host/siteNumber"), message);
    }
}
