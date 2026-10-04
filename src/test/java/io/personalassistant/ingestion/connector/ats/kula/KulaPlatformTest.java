package io.personalassistant.ingestion.connector.ats.kula;

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

class KulaPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class FakeKulaApi implements KulaApi {

        final List<Map<String, Object>> posts = new ArrayList<>();
        final List<Integer> pagesRequested = new ArrayList<>();

        FakeKulaApi withPost(int id, String title, String description, String workplace, String... offices) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job_description", description);
            job.put("workplace", workplace);
            job.put("ats_department", Map.of("id", 1, "name", "Engineering"));
            job.put("offices", java.util.Arrays.stream(offices).map(o -> Map.of("name", o, "location", o)).toList());
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("id", id);
            post.put("title", title);
            post.put("listed", true);
            post.put("kind", "internal_and_external");
            post.put("launch_at", "2026-03-13T16:32:12.000Z");
            post.put("end_at", null);
            post.put("ats_job", job);
            posts.add(post);
            return this;
        }

        @Override
        public JsonNode listJobPosts(String account, int page) {
            if (!account.equals("acme")) {
                throw new AtsApiException(404, "err_account_not_found");
            }
            pagesRequested.add(page);
            int from = Math.min((page - 1) * 99, posts.size());
            int to = Math.min(from + 99, posts.size());
            int pages = Math.max(1, (posts.size() + 98) / 99);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("data", posts.subList(from, to));
            envelope.put("meta", Map.of("count", posts.size(), "page", page, "items", 99, "pages", pages));
            return MAPPER.valueToTree(envelope);
        }
    }

    @Test
    void mapsAPostIntoNormalisedMetadata() {
        FakeKulaApi api = new FakeKulaApi().withPost(5788, "Senior Engineering Manager", "<p>Lead the team.</p>",
                "hybrid", "Bengaluru, Karnataka, India", "Remote, India");

        RawItem item = new KulaPlatform(api).fetch("acme", "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("5788", item.externalId());
        Assertions.assertEquals("https://careers.kula.ai/acme/5788", item.uri());
        Assertions.assertEquals("Acme", item.metadata().get("company"));
        Assertions.assertEquals("Bengaluru, Karnataka, India; Remote, India", item.metadata().get("location"));
        Assertions.assertEquals("kula", item.metadata().get("platform"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals("LEAD", item.metadata().get("seniority"), "a manager title outranks senior");
        Assertions.assertEquals(Instant.parse("2026-03-13T16:32:12Z"), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().contains("Lead the team."));
    }

    @Test
    void aStatedRemoteWorkplaceIsRemote() {
        RawItem item = new KulaPlatform(new FakeKulaApi().withPost(1, "Engineer", "<p>x</p>", "remote", "India"))
                .fetch("acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals(true, item.metadata().get("remote"));
    }

    @Test
    void pagesThroughEveryPage() {
        FakeKulaApi api = new FakeKulaApi();
        for (int i = 0; i < 185; i++) {
            api.withPost(i, "Engineer " + i, "<p>x</p>", "onsite", "Pune, India");
        }

        Assertions.assertEquals(185, new KulaPlatform(api).fetch("acme", BoardFilter.NONE).size());
        Assertions.assertEquals(List.of(1, 2), api.pagesRequested);
    }

    @Test
    void anUnknownAccountIsAMiss() {
        KulaPlatform platform = new KulaPlatform(new FakeKulaApi().withPost(1, "Engineer", "<p>x</p>", "onsite"));

        Assertions.assertFalse(platform.hasBoard("nosuch"));
        Assertions.assertEquals(1, platform.countPostings("acme").orElseThrow());
    }

    @Test
    void theChecksumMovesWhenTheBodyChanges() {
        String before = new KulaPlatform(new FakeKulaApi().withPost(1, "Engineer", "<p>A.</p>", "onsite", "Pune"))
                .fetch("acme", BoardFilter.NONE).get(0).checksum();
        String after = new KulaPlatform(new FakeKulaApi().withPost(1, "Engineer", "<p>A, Kafka.</p>", "onsite", "Pune"))
                .fetch("acme", BoardFilter.NONE).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }
}
