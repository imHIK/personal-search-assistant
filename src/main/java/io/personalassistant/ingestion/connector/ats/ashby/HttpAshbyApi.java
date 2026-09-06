package io.personalassistant.ingestion.connector.ats.ashby;

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

/** HTTP adapter for {@link AshbyApi} against the public posting API (no auth). */
@ApplicationScoped
public class HttpAshbyApi implements AshbyApi {

    /** These boards are public, so the platform — not an account — is the quota's owner. */
    private static final String PLATFORM = "ashby";

    @ConfigProperty(name = "app.ingestion.ashby.base-url",
            defaultValue = "https://api.ashbyhq.com/posting-api/job-board")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.ashby.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpAshbyApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(String boardName) {
        // includeCompensation asks Ashby for structured pay bands, which are far more reliable than
        // scraping a range out of the description prose.
        String url = baseUrl + "/" + encode(boardName) + "?includeCompensation=true";
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
