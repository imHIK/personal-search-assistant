package io.personalassistant.ingestion.connector.ats.eightfold;

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

class EightfoldPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SITE = "explore.jobs.acme.net/acme.com";

    /** A v2 tenant: PCSX answers 403, as Netflix's does. */
    private static class FakeEightfoldApi implements EightfoldApi {

        final List<Map<String, Object>> positions = new ArrayList<>();
        final List<String> detailCalls = new ArrayList<>();
        boolean gated;

        FakeEightfoldApi withPosition(long id, String name, String workOption, String... locations) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("id", id);
            position.put("name", name);
            position.put("location", locations[0]);
            position.put("locations", List.of(locations));
            position.put("department", "Engineering");
            position.put("work_location_option", workOption);
            position.put("t_create", 1790294400L);
            position.put("t_update", 1791029573L);
            position.put("canonicalPositionUrl", "https://explore.jobs.acme.net/careers/job/" + id);
            positions.add(position);
            return this;
        }

        @Override
        public JsonNode listPositions(EightfoldSite site, int start) {
            if (gated) {
                return MAPPER.valueToTree(Map.of("domain", site.domain()));
            }
            return MAPPER.valueToTree(Map.of("count", positions.size(), "positions",
                    positions.subList(Math.min(start, positions.size()), Math.min(start + 10, positions.size()))));
        }

        @Override
        public JsonNode position(EightfoldSite site, String id) {
            detailCalls.add(id);
            return MAPPER.valueToTree(Map.of("id", Long.parseLong(id), "job_description", "<p>Stream things.</p>"));
        }

        @Override
        public JsonNode search(EightfoldSite site, String location, int start) {
            throw new AtsApiException(403, "PCSX is not enabled for this user.");
        }

        @Override
        public JsonNode positionDetails(EightfoldSite site, String id) {
            throw new AtsApiException(403, "PCSX is not enabled for this user.");
        }
    }

    /** A PCSX tenant: v2 answers without a count, as PayPal's does, and location is matched server-side. */
    private static class FakePcsxApi implements EightfoldApi {

        final List<Map<String, Object>> positions = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<String> detailCalls = new ArrayList<>();

        FakePcsxApi withPosition(long id, String name, String... locations) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("id", id);
            position.put("name", name);
            position.put("locations", List.of(locations));
            position.put("department", "Technology");
            position.put("workLocationOption", "onsite");
            position.put("creationTs", 1773964800L);
            position.put("postedTs", 1788739200L);
            position.put("positionUrl", "/careers/job/" + id);
            positions.add(position);
            return this;
        }

        @Override
        public JsonNode listPositions(EightfoldSite site, int start) {
            return MAPPER.valueToTree(Map.of("domain", site.domain()));
        }

        @Override
        public JsonNode position(EightfoldSite site, String id) {
            throw new AtsApiException(404, "not a v2 tenant");
        }

        @Override
        public JsonNode search(EightfoldSite site, String location, int start) {
            queries.add(location + "@" + start);
            List<Map<String, Object>> matching = positions.stream()
                    .filter(p -> location.isEmpty() || ((List<?>) p.get("locations")).stream()
                            .anyMatch(l -> l.toString().toLowerCase().contains(location)))
                    .toList();
            return MAPPER.valueToTree(Map.of("data", Map.of("count", matching.size(), "positions",
                    matching.subList(Math.min(start, matching.size()), Math.min(start + 10, matching.size())))));
        }

        @Override
        public JsonNode positionDetails(EightfoldSite site, String id) {
            detailCalls.add(id);
            return MAPPER.valueToTree(Map.of("data", Map.of("jobDescription", "<p>Trade things.</p>")));
        }
    }

    @Test
    void aPcsxTenantSendsEachLocationTermAsAQueryAndUnionsTheResults() {
        FakePcsxApi api = new FakePcsxApi()
                .withPosition(1, "Java Developer", "Bengaluru, Karnataka, India")
                .withPosition(2, "Java Developer", "Mumbai, Maharashtra, India", "Bengaluru, Karnataka, India")
                .withPosition(3, "Java Developer", "Tokyo, Japan");

        List<RawItem> items = new EightfoldPlatform(api).fetch("morganstanley.eightfold.ai/morganstanley.com",
                BoardFilter.ofLocations(List.of("bengaluru", "mumbai")));

        Assertions.assertEquals(List.of("1", "2"), items.stream().map(RawItem::externalId).toList());
        Assertions.assertEquals(List.of("1", "2"), api.detailCalls, "a position matched twice is fetched once");
        Assertions.assertTrue(api.queries.containsAll(List.of("bengaluru@0", "mumbai@0")));
    }

    @Test
    void aThrottledPcsxProbeDefersRatherThanFallingBackToV2() {
        RateLimitedException throttled =
                new RateLimitedException(RateLimitKey.board("eightfold"), Instant.now().plusSeconds(60));
        FakePcsxApi api = new FakePcsxApi() {
            @Override
            public JsonNode search(EightfoldSite site, String location, int start) {
                throw throttled;
            }
        }.withPosition(1, "Analyst", "Pune");

        Assertions.assertSame(throttled, Assertions.assertThrows(RateLimitedException.class,
                () -> new EightfoldPlatform(api).fetch("paypal.eightfold.ai/paypal.com", BoardFilter.NONE)));
        Assertions.assertFalse(new EightfoldPlatform(api).hasBoard("paypal.eightfold.ai/paypal.com"),
                "resolution still reads it as a miss");
    }

    @Test
    void mapsAPcsxPosition() {
        RawItem item = new EightfoldPlatform(new FakePcsxApi().withPosition(274918944068L, "Payroll Accountant",
                "Bangalore, Karnataka, India"))
                .fetch("paypal.eightfold.ai/paypal.com", "PayPal", BoardFilter.NONE).get(0);

        Assertions.assertEquals("https://paypal.eightfold.ai/careers/job/274918944068", item.uri());
        Assertions.assertEquals("PayPal", item.metadata().get("company"));
        Assertions.assertEquals("Technology", item.metadata().get("team"));
        Assertions.assertEquals(Instant.ofEpochSecond(1773964800L), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().contains("Trade things."));
    }

    @Test
    void aPcsxTenantResolves() {
        Assertions.assertEquals(1, new EightfoldPlatform(new FakePcsxApi().withPosition(1, "Analyst", "Pune"))
                .countPostings("paypal.eightfold.ai/paypal.com").orElseThrow());
    }

    @Test
    void parsesAPairOrAPastedUrlButNotABareNameOrAnotherPlatformsHandle() {
        Assertions.assertEquals(new EightfoldSite("explore.jobs.netflix.net", "netflix.com"),
                EightfoldSite.parse("explore.jobs.netflix.net/netflix.com").orElseThrow());
        Assertions.assertEquals(new EightfoldSite("explore.jobs.netflix.net", "netflix.com"), EightfoldSite.parse(
                "https://explore.jobs.netflix.net/careers?query=Software%20Engineer&location=any"
                        + "&domain=netflix.com&sort_by=relevance&pid=790317912743").orElseThrow());
        Assertions.assertTrue(EightfoldSite.parse("netflix").isEmpty());
        Assertions.assertTrue(EightfoldSite.parse("careers.docusign.com").isEmpty());
        Assertions.assertTrue(EightfoldSite.parse("eofe.fa.us2.oraclecloud.com/BNY-Careers").isEmpty());
        Assertions.assertTrue(EightfoldSite.parse("adobe/external_experienced/wd5").isEmpty());
    }

    @Test
    void theHintIsAppliedToEveryLocationBeforeTheDetailCalls() {
        FakeEightfoldApi api = new FakeEightfoldApi()
                .withPosition(1, "Senior Software Engineer", "onsite", "Los Gatos,California,United States")
                .withPosition(2, "Software Engineer", "hybrid", "Los Gatos,California,United States",
                        "Mumbai,India");

        List<RawItem> items = new EightfoldPlatform(api).fetch(SITE, BoardFilter.ofLocations(List.of("india")));

        Assertions.assertEquals(List.of("2"), api.detailCalls);
        Assertions.assertEquals("Los Gatos,California,United States; Mumbai,India",
                items.get(0).metadata().get("location"));
    }

    @Test
    void mapsAPositionIntoNormalisedMetadata() {
        RawItem item = new EightfoldPlatform(new FakeEightfoldApi()
                .withPosition(790318636029L, "Staff Engineer", "remote", "Remote, India"))
                .fetch(SITE, "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("790318636029", item.externalId());
        Assertions.assertEquals("https://explore.jobs.acme.net/careers/job/790318636029", item.uri());
        Assertions.assertEquals("Acme", item.metadata().get("company"));
        Assertions.assertEquals(true, item.metadata().get("remote"));
        Assertions.assertEquals("STAFF", item.metadata().get("seniority"));
        Assertions.assertEquals(Instant.ofEpochSecond(1790294400L), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().contains("Stream things."));
    }

    @Test
    void pagesThroughTheListing() {
        FakeEightfoldApi api = new FakeEightfoldApi();
        for (int i = 0; i < 25; i++) {
            api.withPosition(i, "Engineer " + i, "onsite", "Mumbai,India");
        }

        Assertions.assertEquals(25, new EightfoldPlatform(api).fetch(SITE, BoardFilter.NONE).size());
    }

    @Test
    void aTenantAnsweringNeitherApiIsAMiss() {
        FakeEightfoldApi api = new FakeEightfoldApi().withPosition(1, "Engineer", "onsite", "Pune");
        api.gated = true;

        Assertions.assertFalse(new EightfoldPlatform(api).hasBoard(SITE));
    }
}
