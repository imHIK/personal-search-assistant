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
    void rejectsABareCompanyName() {
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("paytm"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("adobe/external_experienced"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(""));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(null));
    }
}
