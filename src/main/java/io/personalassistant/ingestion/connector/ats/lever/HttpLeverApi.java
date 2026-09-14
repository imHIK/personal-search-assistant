package io.personalassistant.ingestion.connector.ats.lever;

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

/** HTTP adapter for {@link LeverApi} against the public postings endpoint (no auth). */
@ApplicationScoped
public class HttpLeverApi implements LeverApi {

    /** These boards are public, so the platform — not an account — is the quota's owner. */
    private static final String PLATFORM = "lever";

    @ConfigProperty(name = "app.ingestion.lever.base-url", defaultValue = "https://api.lever.co/v0/postings")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.lever.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpLeverApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listPostings(String site) {
        // mode=json returns structured postings rather than rendered HTML pages.
        String url = baseUrl + "/" + encode(site) + "?mode=json";
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
