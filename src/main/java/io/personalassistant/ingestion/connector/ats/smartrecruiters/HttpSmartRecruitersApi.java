package io.personalassistant.ingestion.connector.ats.smartrecruiters;

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

/** HTTP adapter for {@link SmartRecruitersApi} against the public postings endpoint (no auth). */
@ApplicationScoped
public class HttpSmartRecruitersApi implements SmartRecruitersApi {

    /** These boards are public, so the platform — not an account — is the quota's owner. */
    private static final String PLATFORM = "smartrecruiters";

    @ConfigProperty(name = "app.ingestion.smartrecruiters.base-url",
            defaultValue = "https://api.smartrecruiters.com/v1/companies")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.smartrecruiters.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpSmartRecruitersApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listPostings(String company, int limit, int offset) {
        String url = baseUrl + "/" + encode(company) + "/postings?limit=" + limit + "&offset=" + offset;
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode posting(String company, String postingId) {
        String url = baseUrl + "/" + encode(company) + "/postings/" + encode(postingId);
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
