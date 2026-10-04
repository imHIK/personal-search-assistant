package io.personalassistant.ingestion.connector.ats.oraclehcm;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

        // No region segment: the pod host has no fixed number of labels.
        Assertions.assertEquals("jpmc.fa.oraclecloud.com", site.host());
        Assertions.assertEquals("CX_1001", site.site());
    }

    @Test
    void parsesAUrlWithATrailingJobPath() {
        Assertions.assertEquals("hcbt.fa.em2.oraclecloud.com/CX", OracleHcmSite.parse(
                "https://hcbt.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/job/12345")
                .orElseThrow().toString());
    }

    @Test
    void aBareCompanyNameIsNotASite() {
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse("paytm"));
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse(""));
        Assertions.assertEquals(Optional.empty(), OracleHcmSite.parse(null));
    }

    @Test
    void aVanityHostServingTheCandidateExperienceUiIsAccepted() {
        Assertions.assertEquals("enterpriseplatform.dell.com/careers", OracleHcmSite.parse(
                "https://enterpriseplatform.dell.com/hcmUI/CandidateExperience/en/sites/careers/jobs/preview/294511")
                .orElseThrow().toString());
    }

    @Test
    void aVanityDomainIsRejectedBecauseItDoesNotServeTheApi() {
        Assertions.assertEquals(Optional.empty(),
                OracleHcmSite.parse("https://jobs.akamai.com/en/sites/CX_1/jobs"));
    }

    @Test
    void aWorkdayTripleIsNotAnOracleSite() {
        Assertions.assertEquals(Optional.empty(),
                OracleHcmSite.parse("adobe/external_experienced/wd5"));
    }
}
