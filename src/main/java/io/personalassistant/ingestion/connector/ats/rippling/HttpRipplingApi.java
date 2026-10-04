package io.personalassistant.ingestion.connector.ats.rippling;

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
public class HttpRipplingApi implements RipplingApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "rippling";

    @ConfigProperty(name = "app.ingestion.rippling.base-url",
            defaultValue = "https://api.rippling.com/platform/api/ats/v1/board")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.rippling.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpRipplingApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(String board) {
        return http.getJson(baseUrl + "/" + encode(board) + "/jobs", timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode job(String board, String uuid) {
        String url = baseUrl + "/" + encode(board) + "/jobs/" + encode(uuid);
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
