package io.personalassistant.ingestion.connector.ats.keka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KekaPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOARD_ID = "db770a22-93d1-46f6-af73-c35e960ea796";

    private static class FakeKekaApi implements KekaApi {

        final List<String> boardIdsAsked = new ArrayList<>();

        @Override
        public String careersPage(KekaSite site) {
            return "<script>fetch('/ats/documents/" + BOARD_ID + "/careerportal/e0df.html')</script>";
        }

        @Override
        public JsonNode listJobs(KekaSite site, String boardId) {
            boardIdsAsked.add(boardId);
            return MAPPER.valueToTree(List.of(Map.of(
                    "id", 88095, "title", "Senior Backend Engineer", "description", "<p>Build the CDP.</p>",
                    "departmentName", "Engineering", "publishedOn", "2026-09-10T12:18:37.517Z",
                    "jobLocations", List.of(
                            Map.of("name", "Bangalore office", "city", "Bengaluru", "state", "KA",
                                    "countryName", "India"),
                            Map.of("name", "Head Office - Mumbai", "city", "Mumbai ", "state", "MH",
                                    "countryName", "India")))));
        }
    }

    @Test
    void parsesATenantHostOrAUrlOnIt() {
        Assertions.assertEquals("webklipper.keka.com",
                KekaSite.parse("https://webklipper.keka.com/careers/jobdetails/80114").orElseThrow().host());
        Assertions.assertEquals("webklipper.keka.com", KekaSite.parse("webklipper.keka.com").orElseThrow().host());
        Assertions.assertTrue(KekaSite.parse("webengage").isEmpty());
        Assertions.assertTrue(KekaSite.parse("careers.docusign.com").isEmpty());
    }

    @Test
    void readsTheBoardIdOffTheCareersPageAndMapsTheJobs() {
        FakeKekaApi api = new FakeKekaApi();

        RawItem item = new KekaPlatform(api).fetch("webklipper.keka.com", "WebEngage", BoardFilter.NONE).get(0);

        Assertions.assertEquals(List.of(BOARD_ID), api.boardIdsAsked);
        Assertions.assertEquals("88095", item.externalId());
        Assertions.assertEquals("https://webklipper.keka.com/careers/jobdetails/88095", item.uri());
        Assertions.assertEquals("WebEngage", item.metadata().get("company"));
        Assertions.assertEquals("Bengaluru, KA, India; Mumbai, MH, India", item.metadata().get("location"));
        Assertions.assertEquals("keka", item.metadata().get("platform"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertTrue(item.text().contains("Build the CDP."));
    }

    @Test
    void aBareNameIsAMissWithoutANetworkCall() {
        FakeKekaApi api = new FakeKekaApi();

        Assertions.assertFalse(new KekaPlatform(api).hasBoard("webengage"));
        Assertions.assertTrue(api.boardIdsAsked.isEmpty());
    }

    @Test
    void aPageWithNoBoardIdIsAMissRatherThanAThrow() {
        KekaApi blank = new FakeKekaApi() {
            @Override
            public String careersPage(KekaSite site) {
                return "<html></html>";
            }
        };

        Assertions.assertFalse(new KekaPlatform(blank).hasBoard("other.keka.com"));
    }
}
