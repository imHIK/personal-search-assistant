package io.personalassistant.ingestion.connector.ats.ainterviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AInterviewsPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AInterviewsApi board(String description) {
        Map<String, Object> job = Map.of(
                "id", "418", "title", "Senior Backend Engineer - Java", "location", "Bangalore",
                "description", description, "company_description", "<p>About Acme.</p>",
                "category", "Engineering", "posted_date", "2025-10-22T07:04:42.168972+00:00");
        return board -> {
            if (!board.equals("acme_ho")) {
                throw new AtsApiException(404, "no such board");
            }
            return MAPPER.valueToTree(Map.of("jobs", List.of(job), "filters", Map.of()));
        };
    }

    @Test
    void mapsAJobIntoNormalisedMetadata() {
        RawItem item = new AInterviewsPlatform(board("<p>Build services.</p>"))
                .fetch("acme_ho", "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("418", item.externalId());
        Assertions.assertEquals("https://ainterviews.com/job_board/acme_ho/job/418/", item.uri());
        Assertions.assertEquals("Acme", item.metadata().get("company"));
        Assertions.assertEquals("Bangalore", item.metadata().get("location"));
        Assertions.assertEquals("ainterviews", item.metadata().get("platform"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals("SENIOR", item.metadata().get("seniority"));
        Assertions.assertEquals(Instant.parse("2025-10-22T07:04:42.168972Z"), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().startsWith("<p>Build services."), "the role leads the body");
    }

    @Test
    void theChecksumMovesWhenTheBodyChanges() {
        String before = new AInterviewsPlatform(board("<p>Original.</p>"))
                .fetch("acme_ho", BoardFilter.NONE).get(0).checksum();
        String after = new AInterviewsPlatform(board("<p>Original, with Kafka.</p>"))
                .fetch("acme_ho", BoardFilter.NONE).get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void anUnknownOrEmptyBoardIsAMiss() {
        Assertions.assertFalse(new AInterviewsPlatform(board("")).hasBoard("nosuch"));
        AInterviewsApi empty = board -> MAPPER.valueToTree(Map.of("jobs", List.of()));
        Assertions.assertFalse(new AInterviewsPlatform(empty).hasBoard("acme_ho"));
        Assertions.assertEquals(1, new AInterviewsPlatform(board("")).countPostings("acme_ho").orElseThrow());
    }
}
