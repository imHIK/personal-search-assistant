package io.personalassistant.ingestion.connector.ats.turbohire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TurboHirePlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class FakeTurboHireApi implements TurboHireApi {

        final List<Map<String, Object>> jobs = new ArrayList<>();
        final List<String> detailCalls = new ArrayList<>();
        final List<String> referers = new ArrayList<>();
        RuntimeException detailFailure;

        FakeTurboHireApi withJob(String id, String title, String address, boolean showClient) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("JobId", id);
            job.put("JobTitle", title);
            job.put("Department", "Engineering");
            job.put("UpdatedDate", "2026-10-01T09:11:52.6866667");
            job.put("PublishedDate", "2026-09-11T22:13:11.0255788Z");
            job.put("ExpiryDates", Map.of("CAREERPAGE", "2026-12-31T00:00:00"));
            job.put("Location", "[{\"Address\":\"" + address + "\",\"PlaceId\":\"x\"}]");
            job.put("ShowClientName", showClient);
            job.put("ClientName", showClient ? "Cleartrip" : "[masked]");
            job.put("CreatedByEmail", "recruiter@acme.com");
            job.put("CreatedByName", "A Recruiter");
            jobs.add(job);
            return this;
        }

        @Override
        public JsonNode anonymousToken(TurboHireSite site) {
            referers.add(site.referer());
            return MAPPER.valueToTree(Map.of("access_token", "tkn"));
        }

        @Override
        public JsonNode organization(TurboHireSite site, String token) {
            if (!site.account().equals("acme")) {
                throw new AtsApiException(404, "no such account");
            }
            return MAPPER.valueToTree(Map.of("OrgID", "org-1", "OrgName", "Acme Internet Private Limited"));
        }

        @Override
        public JsonNode careerPageJobs(TurboHireSite site, String token, String orgId) {
            return MAPPER.valueToTree(Map.of("Result", jobs));
        }

        @Override
        public JsonNode job(TurboHireSite site, String token, String jobId) {
            detailCalls.add(jobId);
            if (detailFailure != null) {
                throw detailFailure;
            }
            return MAPPER.valueToTree(Map.of("IsPublic", true, "JobDescriptionV2", "<p>Build checkout.</p>",
                    "ClientDescV2", "<p>About Acme.</p>"));
        }
    }

    @Test
    void parsesAHostAUrlOrTheBareAccount() {
        Assertions.assertEquals("flipkart",
                TurboHireSite.parse("https://Flipkart.turbohire.co/careerpage/4d75").orElseThrow().account());
        Assertions.assertEquals("olacareers", TurboHireSite.parse("olacareers").orElseThrow().account());
        Assertions.assertTrue(TurboHireSite.parse("careers.docusign.com").isEmpty());
        Assertions.assertTrue(TurboHireSite.parse("adobe/external_experienced/wd5").isEmpty());
    }

    @Test
    void theTokenIsRequestedAsTheAccountsOwnPage() {
        FakeTurboHireApi api = new FakeTurboHireApi().withJob("j1", "Engineer", "Pune, India", false);

        new TurboHirePlatform(api).fetch("acme.turbohire.co", BoardFilter.NONE);

        Assertions.assertEquals(List.of("https://acme.turbohire.co/"), api.referers);
    }

    @Test
    void theHintIsAppliedBeforeTheDetailCalls() {
        FakeTurboHireApi api = new FakeTurboHireApi()
                .withJob("j1", "Senior Engineer", "Bengaluru, Karnataka, India", false)
                .withJob("j2", "Store Manager", "Dubai, United Arab Emirates", false);

        List<RawItem> items = new TurboHirePlatform(api).fetch("acme", BoardFilter.ofLocations(List.of("india")));

        Assertions.assertEquals(List.of("j1"), items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(List.of("j1"), api.detailCalls);
    }

    @Test
    void mapsAJobAndNeverCopiesTheRecruiter() {
        RawItem item = new TurboHirePlatform(new FakeTurboHireApi()
                .withJob("j1", "Senior Engineer", "Bengaluru, Karnataka, India", false))
                .fetch("acme", "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("https://acme.turbohire.co/job/publicjobs/j1", item.uri());
        Assertions.assertEquals("Acme", item.metadata().get("company"), "a masked client falls back to the label");
        Assertions.assertEquals("Bengaluru, Karnataka, India", item.metadata().get("location"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals(Instant.parse("2026-09-11T22:13:11.0255788Z"), item.metadata().get("postedAt"));
        Assertions.assertEquals(Instant.parse("2026-12-31T00:00:00Z"), item.expiresAt());
        Assertions.assertTrue(item.text().startsWith("<p>Build checkout."));
        String everything = item.metadata() + " " + item.raw() + " " + item.text();
        Assertions.assertFalse(everything.contains("recruiter@acme.com"));
        Assertions.assertFalse(everything.contains("A Recruiter"));
    }

    @Test
    void aShownClientNamesTheCompany() {
        RawItem item = new TurboHirePlatform(new FakeTurboHireApi().withJob("j1", "Engineer", "Pune, India", true))
                .fetch("acme", "Flipkart", BoardFilter.NONE).get(0);

        Assertions.assertEquals("Cleartrip", item.metadata().get("company"));
    }

    @Test
    void aThrottledDetailCallDefersTheBoard() {
        FakeTurboHireApi api = new FakeTurboHireApi().withJob("j1", "Engineer", "Pune, India", false);
        api.detailFailure = new RateLimitedException(RateLimitKey.board("turbohire"), Instant.now().plusSeconds(60));

        Assertions.assertThrows(RateLimitedException.class,
                () -> new TurboHirePlatform(api).fetch("acme", BoardFilter.NONE));
    }

    @Test
    void aFailedDetailCallSkipsOnlyThatJob() {
        FakeTurboHireApi api = new FakeTurboHireApi().withJob("j1", "Engineer", "Pune, India", false);
        api.detailFailure = new AtsApiException(404, "closed");

        Assertions.assertTrue(new TurboHirePlatform(api).fetch("acme", BoardFilter.NONE).isEmpty());
    }

    @Test
    void anUnknownAccountIsAMiss() {
        TurboHirePlatform platform =
                new TurboHirePlatform(new FakeTurboHireApi().withJob("j1", "Engineer", "Pune, India", false));

        Assertions.assertFalse(platform.hasBoard("nosuch"));
        Assertions.assertEquals(1, platform.countPostings("acme.turbohire.co").orElseThrow());
    }
}
