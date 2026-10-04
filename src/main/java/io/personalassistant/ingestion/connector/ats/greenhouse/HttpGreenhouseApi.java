package io.personalassistant.ingestion.connector.ats.greenhouse;

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
public class HttpGreenhouseApi implements GreenhouseApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "greenhouse";

    @ConfigProperty(name = "app.ingestion.greenhouse.base-url",
            defaultValue = "https://boards-api.greenhouse.io/v1/boards")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.greenhouse.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpGreenhouseApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(String boardToken) {
        // content=true: without it the description needs a request per posting.
        String url = baseUrl + "/" + encode(boardToken) + "/jobs?content=true";
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
