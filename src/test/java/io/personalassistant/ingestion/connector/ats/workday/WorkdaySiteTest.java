package io.personalassistant.ingestion.connector.ats.workday;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Parsing the {@code tenant/site/wd} triple. This is what tells the connector a bare company name is
 * not a Workday site, so getting it wrong either costs a speculative request on every resolution or
 * silently refuses a valid site.
 */
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
        // Reading the triple off a careers page is the documented way to find it, so the URL that page
        // lives at must work directly.
        WorkdaySite site =
                WorkdaySite.parse("https://adobe.wd5.myworkdayjobs.com/external_experienced").orElseThrow();

        Assertions.assertEquals(new WorkdaySite("adobe", "external_experienced", "wd5"), site);
    }

    @Test
    void rejectsABareCompanyName() {
        // The important case: resolution asks every platform about every name, and there is no tenant
        // to guess, so this must be a cheap no rather than a speculative request.
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("paytm"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse("adobe/external_experienced"));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(""));
        Assertions.assertEquals(Optional.empty(), WorkdaySite.parse(null));
    }
}
