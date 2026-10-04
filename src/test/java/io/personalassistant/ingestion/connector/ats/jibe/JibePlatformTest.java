package io.personalassistant.ingestion.connector.ats.jibe;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JibePlatformTest {

    private static final String HOST = "careers.acme.com";

    private static FakeJibeApi board() {
        return new FakeJibeApi()
                .withJob(HOST, "101", "Senior Software Engineer", "Bengaluru, Karnataka, India",
                        "IN-KA-Bengaluru", "<p>Build distributed systems.</p>")
                .withJob(HOST, "102", "Account Executive", "Washington, United States",
                        "US-WA-Remote (SEA Area)", "<p>Sell things.</p>");
    }

    private static List<RawItem> fetch(FakeJibeApi api, String handle) {
        return new JibePlatform(api).fetch(handle, BoardFilter.NONE);
    }

    @Test
    void parsesAHostOrAPastedJobUrlButNotABareNameOrAnotherPlatformsHandle() {
        Assertions.assertEquals(HOST, JibeSite.parse("careers.acme.com").orElseThrow().host());
        Assertions.assertEquals(HOST,
                JibeSite.parse("https://Careers.Acme.com/careers-home/jobs/30400?lang=en").orElseThrow().host());
        Assertions.assertTrue(JibeSite.parse("acme").isEmpty());
        Assertions.assertTrue(JibeSite.parse("adobe/external_experienced/wd5").isEmpty());
        Assertions.assertTrue(JibeSite.parse("eofe.fa.us2.oraclecloud.com/BNY-Careers").isEmpty());
    }

    @Test
    void aBareNameIsAMissWithoutANetworkCall() {
        FakeJibeApi api = board();

        Assertions.assertFalse(new JibePlatform(api).hasBoard("acme"));
        Assertions.assertTrue(api.pagesRequested.isEmpty());
    }

    @Test
    void aHostThatIsNotAJibeSiteIsAMissRatherThanAThrow() {
        Assertions.assertFalse(new JibePlatform(board()).hasBoard("careers.other.com"));
    }

    @Test
    void countsTheWholeBoard() {
        Assertions.assertEquals(2, new JibePlatform(board()).countPostings(HOST).orElseThrow());
    }

    @Test
    void mapsAJobIntoNormalisedMetadata() {
        RawItem item = fetch(board(), "https://careers.acme.com/careers-home/jobs/101").get(0);

        Assertions.assertEquals("101", item.externalId());
        Assertions.assertEquals("Senior Software Engineer", item.title());
        Assertions.assertEquals("https://careers.acme.com/jobs/101", item.uri());
        Assertions.assertEquals("https://uscareers-acme.icims.com/jobs/101/login", item.metadata().get("applyUrl"));
        Assertions.assertEquals("Bengaluru, Karnataka, India", item.metadata().get("location"));
        Assertions.assertEquals("jibe", item.metadata().get("platform"));
        Assertions.assertEquals("Engineering", item.metadata().get("team"));
        Assertions.assertEquals("SENIOR", item.metadata().get("seniority"));
        Assertions.assertEquals(Instant.parse("2026-09-29T20:15:00Z"), item.metadata().get("postedAt"));
        Assertions.assertTrue(item.text().contains("distributed systems"));
        Assertions.assertTrue(item.text().contains("Java."), "qualifications are part of the body");
    }

    @Test
    void remoteIsReadFromTheRequisitionLabelWhenTheLocationOmitsIt() {
        RawItem item = fetch(board(), HOST).get(1);

        Assertions.assertEquals("Washington, United States", item.metadata().get("location"));
        Assertions.assertEquals(true, item.metadata().get("remote"));
    }

    @Test
    void theLabelNamesTheCompanyInPlaceOfTheHost() {
        RawItem item = new JibePlatform(board()).fetch(HOST, "Acme", BoardFilter.NONE).get(0);

        Assertions.assertEquals("Acme", item.metadata().get("company"));
    }

    @Test
    void theChecksumMovesWhenTheBodyChanges() {
        String before = fetch(new FakeJibeApi()
                .withJob(HOST, "1", "Engineer", "Pune, India", "IN-Pune", "<p>Original.</p>"), HOST)
                .get(0).checksum();
        String after = fetch(new FakeJibeApi()
                .withJob(HOST, "1", "Engineer", "Pune, India", "IN-Pune", "<p>Original, with Kafka.</p>"), HOST)
                .get(0).checksum();

        Assertions.assertNotEquals(before, after);
    }

    @Test
    void pagesThroughABoardLargerThanOnePage() {
        FakeJibeApi api = new FakeJibeApi();
        for (int i = 0; i < 257; i++) {
            api.withJob(HOST, "id-" + i, "Engineer " + i, "Pune, India", "IN-Pune", "<p>Work.</p>");
        }

        Assertions.assertEquals(257, fetch(api, HOST).size());
        Assertions.assertEquals(List.of(1, 2, 3), api.pagesRequested);
    }
}
