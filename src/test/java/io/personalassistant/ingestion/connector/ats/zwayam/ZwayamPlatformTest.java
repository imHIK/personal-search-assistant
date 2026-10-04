package io.personalassistant.ingestion.connector.ats.zwayam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class ZwayamPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN = "careers.acme.fit";

    private static class FakeZwayamApi implements ZwayamApi {

        final List<Map<String, Object>> hits = new ArrayList<>();
        final List<String> detailCalls = new ArrayList<>();

        FakeZwayamApi withJob(int id, String title, String location) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", id);
            source.put("jobTitle", title);
            source.put("jobUrl", "job-" + id);
            source.put("locationSeparatedbySlash", location);
            source.put("companyId", 15470);
            source.put("createdDate", 1785759035000L);
            source.put("modifiedDate", 1788329880000L);
            hits.add(Map.of("_source", source));
            return this;
        }

        @Override
        public JsonNode search(String domain, int offset) {
            if (!domain.equals(DOMAIN)) {
                return MAPPER.valueToTree(Map.of("code", 200));
            }
            List<Map<String, Object>> page = hits.subList(Math.min(offset, hits.size()),
                    Math.min(offset + 10, hits.size()));
            return MAPPER.valueToTree(Map.of("data", Map.of(
                    "data", page, "totalCount", hits.size(), "hasMoreData", offset + 10 < hits.size())));
        }

        @Override
        public JsonNode job(String companyId, String jobUrl) {
            detailCalls.add(jobUrl);
            if (jobUrl.equals("job-404")) {
                throw new AtsApiException(404, "gone");
            }
            return MAPPER.valueToTree(Map.of("role", "<p>Secure the apps.</p>",
                    "longDescription", "<p>About Acme.</p>", "departmentName", "Security"));
        }

        @Override
        public JsonNode careerSite(String companyId) {
            return MAPPER.valueToTree(Map.of("reponseObject",
                    Map.of("company", Map.of("folder", "acme", "companyName", "Acme.Fit"))));
        }
    }

    @Test
    void theHintIsAppliedBeforeTheDetailCallsAndAFailedDetailSkipsOnlyThatJob() {
        FakeZwayamApi api = new FakeZwayamApi()
                .withJob(1, "Application Security Engineer", "Bangalore")
                .withJob(2, "Personal Trainer", "Nagpur")
                .withJob(404, "Engineer", "Pune/Bangalore");

        List<RawItem> items = new ZwayamPlatform(api).fetch(DOMAIN, BoardFilter.ofLocations(List.of("bangalore")));

        Assertions.assertEquals(List.of("job-1", "job-404"), api.detailCalls);
        Assertions.assertEquals(List.of("1"), items.stream().map(RawItem::externalId).toList());
    }

    @Test
    void mapsAJobIntoNormalisedMetadata() {
        RawItem item = new ZwayamPlatform(new FakeZwayamApi().withJob(1, "Senior Engineer", "Bangalore"))
                .fetch("https://careers.acme.fit/acme/jobview/job-1?id=1", "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("https://careers.acme.fit/acme/jobview/job-1?id=1", item.uri());
        Assertions.assertEquals("Acme.Fit", item.metadata().get("company"), "the site's own name wins");
        Assertions.assertEquals("zwayam", item.metadata().get("platform"));
        Assertions.assertEquals("Security", item.metadata().get("team"));
        Assertions.assertEquals(Instant.ofEpochMilli(1785759035000L), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().startsWith("<p>Secure the apps."), "the role leads the body");
    }

    @Test
    void pagesThroughTheSearch() {
        FakeZwayamApi api = new FakeZwayamApi();
        for (int i = 0; i < 25; i++) {
            api.withJob(i, "Engineer " + i, "Bangalore");
        }

        Assertions.assertEquals(25, new ZwayamPlatform(api).fetch(DOMAIN, BoardFilter.NONE).size());
    }

    @Test
    void anUnknownDomainOrABareNameIsAMiss() {
        ZwayamPlatform platform = new ZwayamPlatform(new FakeZwayamApi().withJob(1, "Engineer", "Pune"));

        Assertions.assertFalse(platform.hasBoard("careers.other.com"));
        Assertions.assertFalse(platform.hasBoard("acme"));
        Assertions.assertTrue(platform.hasBoard(DOMAIN));
    }
}
