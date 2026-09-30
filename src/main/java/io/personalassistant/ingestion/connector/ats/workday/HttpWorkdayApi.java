package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpWorkdayApi implements WorkdayApi {

    /** Public boards: the platform, not an account, owns the quota. */
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
        // The paging window travels in the body. appliedFacets stays empty: its keys are tenant-specific
        // GUIDs, whereas searchText is the same on every tenant.
        String body = "{\"appliedFacets\":{},\"limit\":" + limit
                + ",\"offset\":" + offset
                + ",\"searchText\":" + jsonString(searchText) + "}";
        return http.postJson(site.apiRoot() + "/jobs", body, timeoutSeconds, rateLimit());
    }

    /** Hand-rolled: this is the only JSON the class builds. */
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
