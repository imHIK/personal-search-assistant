package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link WorkdayApi} against the public career-site API (no auth). */
@ApplicationScoped
public class HttpWorkdayApi implements WorkdayApi {

    /** These boards are public, so the platform — not an account — is the quota's owner. */
    private static final String PLATFORM = "workday";

    @ConfigProperty(name = "app.ingestion.workday.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpWorkdayApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode searchJobs(WorkdaySite site, int limit, int offset, String searchText) {
        // The paging window travels in the body; there is no query-string form of this call.
        // appliedFacets stays empty: its keys are tenant-specific GUIDs that cannot be derived from a
        // place name, whereas searchText is the same field on every tenant.
        String body = "{\"appliedFacets\":{},\"limit\":" + limit
                + ",\"offset\":" + offset
                + ",\"searchText\":" + jsonString(searchText) + "}";
        return http.postJson(site.apiRoot() + "/jobs", body, timeoutSeconds, rateLimit());
    }

    /**
     * A JSON string literal. Hand-rolled rather than pulling in a mapper because this request body is
     * the only JSON this class builds, and the one value that is not ours is now user-supplied.
     */
    private static String jsonString(String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');   // control characters carry no search meaning
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    @Override
    public JsonNode posting(WorkdaySite site, String externalPath) {
        String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
        return http.getJson(site.apiRoot() + path, timeoutSeconds, rateLimit());
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
