package io.personalassistant.ingestion.connector.ats.freshteam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FreshteamPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = "acme.freshteam.com";

    private static FreshteamApi board(String description) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", 4000063953L);
        job.put("title", " Senior Backend Engineer");
        job.put("description", description);
        job.put("deleted", false);
        job.put("remote", false);
        job.put("closing_date", null);
        job.put("created_at", "2025-09-02T11:58:56.000Z");
        job.put("url", "https://careers.acme.com/?jobId=Icr6_JHVX9Fz");
        job.put("branch_id", 4000033745L);
        job.put("job_role_id", 4000145537L);
        Map<String, Object> deleted = new LinkedHashMap<>(job);
        deleted.put("id", 2L);
        deleted.put("deleted", true);
        Map<String, Object> body = Map.of(
                "jobs", List.of(job, deleted),
                "branches", List.of(Map.of("id", 4000033745L, "city", "Bengaluru", "location", "Bengaluru, India")),
                "job_roles", List.of(Map.of("id", 4000145537L, "name", "Engineering")));
        return site -> {
            if (!site.host().equals(HOST)) {
                throw new AtsApiException("not JSON: an unknown subdomain answers with a page");
            }
            return MAPPER.valueToTree(body);
        };
    }

    @Test
    void parsesTheAccountHostOrAUrlOnIt() {
        Assertions.assertEquals(HOST, FreshteamSite.parse("https://Acme.freshteam.com/jobs").orElseThrow().host());
        Assertions.assertTrue(FreshteamSite.parse("acme").isEmpty());
        Assertions.assertTrue(FreshteamSite.parse("acme.keka.com").isEmpty());
    }

    @Test
    void mapsAJobResolvingItsBranchAndRoleAndSkippingDeletedOnes() {
        List<RawItem> items = new FreshteamPlatform(board("<p>Build lending.</p>")).fetch(HOST, "Acme", BoardFilter.NONE);

        Assertions.assertEquals(1, items.size(), "the deleted job is skipped");
        RawItem item = items.get(0);
        Assertions.assertEquals("Senior Backend Engineer", item.title());
        Assertions.assertEquals("https://careers.acme.com/?jobId=Icr6_JHVX9Fz", item.uri());
        Assertions.assertEquals("Bengaluru, India", item.metadata().get("location"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals("Acme", item.metadata().get("company"));
        Assertions.assertEquals("freshteam", item.metadata().get("platform"));
        Assertions.assertEquals(Instant.parse("2025-09-02T11:58:56Z"), item.metadata().get("postedAt"));
    }

    @Test
    void anUnknownSubdomainOrABareNameIsAMiss() {
        FreshteamPlatform platform = new FreshteamPlatform(board("<p>x</p>"));

        Assertions.assertFalse(platform.hasBoard("other.freshteam.com"));
        Assertions.assertFalse(platform.hasBoard("acme"));
        Assertions.assertEquals(2, platform.countPostings(HOST).orElseThrow());
    }

    @Test
    void theChecksumMovesWhenTheBodyChanges() {
        String before = new FreshteamPlatform(board("<p>A.</p>")).fetch(HOST, BoardFilter.NONE).get(0).checksum();
        String after = new FreshteamPlatform(board("<p>A, Kafka.</p>")).fetch(HOST, BoardFilter.NONE).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }
}
