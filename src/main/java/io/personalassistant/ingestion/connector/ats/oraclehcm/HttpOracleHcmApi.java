package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpOracleHcmApi implements OracleHcmApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "oraclehcm";

    @ConfigProperty(name = "app.ingestion.oraclehcm.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpOracleHcmApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    /**
     * The finder ({@code findReqs;name=value,...}) must keep its semicolons and commas, so it is assembled
     * from already-encoded parts.
     */
    @Override
    public JsonNode searchRequisitions(OracleHcmSite site, String keyword, String locationId, int limit,
                                       int offset) {
        StringBuilder finder = new StringBuilder("findReqs;siteNumber=").append(enc(site.site()));
        if (keyword != null && !keyword.isBlank()) {
            finder.append(",keyword=").append(enc(keyword));
        }
        if (locationId != null && !locationId.isBlank()) {
            finder.append(",locationId=").append(enc(locationId));
        }
        finder.append(",limit=").append(limit).append(",offset=").append(offset)
                .append(",sortBy=POSTING_DATES_DESC");
        String url = site.apiRoot() + "/recruitingCEJobRequisitions?onlyData=true"
                + "&expand=requisitionList.secondaryLocations,flexFieldsFacet.values"
                + "&finder=" + finder;
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode locationSuggestions(OracleHcmSite site, String term) {
        String url = site.apiRoot() + "/recruitingCESearchAutoSuggestions?onlyData=true&limit=20"
                + "&finder=findByLoc;string=" + enc(term);
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode requisition(OracleHcmSite site, String id) {
        // ById takes quoted values, unlike findReqs. The quotes are part of the syntax, not of the id.
        String finder = "ById;Id=%22" + enc(id) + "%22,siteNumber=%22" + enc(site.site()) + "%22";
        String url = site.apiRoot() + "/recruitingCEJobRequisitionDetails?expand=all&onlyData=true"
                + "&finder=" + finder;
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
