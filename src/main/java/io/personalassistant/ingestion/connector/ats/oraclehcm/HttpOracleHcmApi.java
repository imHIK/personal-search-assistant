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

/** HTTP adapter for {@link OracleHcmApi} against the public candidate-experience API (no auth). */
@ApplicationScoped
public class HttpOracleHcmApi implements OracleHcmApi {

    /** These sites are public, so the platform — not an account — is the quota's owner. */
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
     * {@inheritDoc}
     *
     * <p>The filter travels in Oracle's {@code finder} syntax — {@code findReqs;name=value,...} — which
     * is a query-string value containing semicolons and commas that must survive intact, so the finder
     * is assembled from already-encoded parts rather than encoded wholesale.
     */
    @Override
    public JsonNode searchRequisitions(OracleHcmSite site, String keyword, int limit, int offset) {
        StringBuilder finder = new StringBuilder("findReqs;siteNumber=").append(enc(site.site()));
        if (keyword != null && !keyword.isBlank()) {
            finder.append(",keyword=").append(enc(keyword));
        }
        finder.append(",limit=").append(limit).append(",offset=").append(offset)
                .append(",sortBy=POSTING_DATES_DESC");
        String url = site.apiRoot() + "/recruitingCEJobRequisitions?onlyData=true"
                + "&expand=requisitionList.secondaryLocations,flexFieldsFacet.values"
                + "&finder=" + finder;
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
