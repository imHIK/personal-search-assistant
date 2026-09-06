package io.personalassistant.ingestion.connector.ats.oraclehcm;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Handle parsing — the part a user has to get right, so every real-world shape is covered. */
class OracleHcmSiteTest {

    @Test
    void parsesTheHostSitePair() {
        OracleHcmSite site = OracleHcmSite.parse("eofe.fa.us2.oraclecloud.com/BNY-Careers").orElseThrow();

        Assertions.assertEquals("eofe.fa.us2.oraclecloud.com", site.host());
        Assertions.assertEquals("BNY-Careers", site.site());
    }

    @Test
    void parsesAPastedCareerSiteUrl() {
        OracleHcmSite site = OracleHcmSite.parse(
                "https://jpmc.fa.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1001/jobs")
                .orElseThrow();

        // Note the missing region segment: the pod host is not a fixed number of labels.
        Assertions.assertEquals("jpmc.fa.oraclecloud.com", site.host());
        Assertions.assertEquals("CX_1001", site.site());
    }

    @Test
    void parsesAUrlWithATrailingJobPath() {
        // What a user actually copies is a job page, not the bare site.
        Assertions.assertEquals("hcbt.fa.em2.oraclecloud.com/CX", OracleHcmSite.parse(
                "https://hcbt.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/job/12345")
                .orElseThrow().toString());
    }

    @Test
    void aBareCompanyNameIsNotASite() {
        // This is what tells the connector to keep probing the other platforms.
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse("paytm"));
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse(""));
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse(null));
    }

    @Test
    void aVanityDomainIsRejectedBecauseItDoesNotServeTheApi() {
        // careers.americanexpress.com and jobs.akamai.com serve the UI but not the REST API, so
        // accepting them would produce a site that resolves and then fails every fetch.
        Assertions.assertEquals(Optional.empty(),
                OracleHcmSite.parse("https://jobs.akamai.com/en/sites/CX_1/jobs"));
    }

    @Test
    void aWorkdayTripleIsNotAnOracleSite() {
        Assertions.assertEquals(Optional.empty(),
                OracleHcmSite.parse("adobe/external_experienced/wd5"));
    }
}
