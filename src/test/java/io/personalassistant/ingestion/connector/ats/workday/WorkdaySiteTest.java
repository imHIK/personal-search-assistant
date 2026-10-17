package io.personalassistant.ingestion.connector.ats.workday;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkdaySiteTest {

    @Test
    void parsesATriple() {
        WorkdaySite site = WorkdaySite.parse("adobe/external_experienced/wd5").orElseThrow();

        Assertions.assertEquals("adobe", site.tenant());
        Assertions.assertEquals("external_experienced", site.site());
        Assertions.assertEquals("wd5", site.wd());
        Assertions.assertEquals("https://adobe.wd5.myworkdayjobs.com/wday/cxs/adobe/external_experienced",
                site.apiRoot());
    }

    @Test
    void parsesAPastedCareerSiteUrl() {
        WorkdaySite site =
                WorkdaySite.parse("https://adobe.wd5.myworkdayjobs.com/external_experienced").orElseThrow();

        Assertions.assertEquals(new WorkdaySite("adobe", "external_experienced", "wd5"), site);
    }

    @Test
    void aLocaleSegmentInAPastedUrlIsNotTheSite() {
        Assertions.assertEquals(new WorkdaySite("jiostar", "JioStar", "wd102"), WorkdaySite.parse(
                "https://jiostar.wd102.myworkdayjobs.com/en-GB/JioStar/details/Software-Engineer_JR12421?q=x")
                .orElseThrow());
        Assertions.assertEquals(new WorkdaySite("mastercard", "CorporateCareers", "wd1"), WorkdaySite.parse(
                "https://mastercard.wd1.myworkdayjobs.com/en-US/CorporateCareers/job/Software-Engineer-II_R-288000")
                .orElseThrow());
        Assertions.assertEquals(new WorkdaySite("cisco", "Cisco_Careers", "wd5"), WorkdaySite.parse(
                "https://cisco.wd5.myworkdayjobs.com/Cisco_Careers/job/Bangalore-India/Software-Engineer_2023390-1")
                .orElseThrow());
    }

    @Test
    void aFrontEndSuffixIsKeptApartFromTheSite() {
        WorkdaySite site = WorkdaySite.parse("cisco/Cisco_Careers/wd5@https://careers.cisco.com/global/en/job/")
                .orElseThrow();

        Assertions.assertEquals(new WorkdaySite("cisco", "Cisco_Careers", "wd5",
                "https://careers.cisco.com/global/en/job/"), site);
        Assertions.assertEquals("cisco/Cisco_Careers/wd5", site.toString());
        Assertions.assertEquals("https://cisco.wd5.myworkdayjobs.com/wday/cxs/cisco/Cisco_Careers", site.apiRoot());
        Assertions.assertEquals(Optional.of("https://careers.cisco.com/global/en/job/2019628"),
                site.jobUrl("2019628"));
    }

    @Test
    void aPastedUrlTakesAFrontEndToo() {
        Assertions.assertEquals(new WorkdaySite("cisco", "Cisco_Careers", "wd5", "https://careers.cisco.com/job/"),
                WorkdaySite.parse("https://cisco.wd5.myworkdayjobs.com/Cisco_Careers@https://careers.cisco.com/job/")
                        .orElseThrow());
    }

    @Test
    void withoutAFrontEndThereIsNoJobUrl() {
        Assertions.assertEquals(Optional.empty(),
                WorkdaySite.parse("cisco/Cisco_Careers/wd5").orElseThrow().jobUrl("2019628"));
    }

    @Test
    void aFrontEndThatIsNotAUrlIsRejected() {
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("cisco/Cisco_Careers/wd5@careers.cisco.com"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("cisco/Cisco_Careers/wd5@"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("paytm@https://careers.paytm.com/"));
    }

    @Test
    void rejectsABareCompanyName() {
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("paytm"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("adobe/external_experienced"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(""));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(null));
    }
}
