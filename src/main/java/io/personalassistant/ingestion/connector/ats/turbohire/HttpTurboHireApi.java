package io.personalassistant.ingestion.connector.ats.turbohire;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpTurboHireApi implements TurboHireApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "turbohire";

    /** The public career page; see {@link TurboHireApi#careerPageJobs}. */
    private static final int CAREER_PAGE = 0;

    /** One of several shards behind the careers pages; each serves every account. */
    @ConfigProperty(name = "app.ingestion.turbohire.base-url",
            defaultValue = "https://thapi-stage0.azurewebsites.net/api")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.turbohire.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpTurboHireApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode anonymousToken(TurboHireSite site) {
        return http.getJson(baseUrl + "/token/noauth", Map.of("Referer", site.referer()), timeoutSeconds,
                rateLimit());
    }

    @Override
    public JsonNode organization(TurboHireSite site, String token) {
        return http.getJson(baseUrl + "/publicorganizations?accountName=" + enc(site.account()),
                headers(site, token), timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode careerPageJobs(TurboHireSite site, String token, String orgId) {
        String url = baseUrl + "/careerpagev2/filteredjobs?orgId=" + enc(orgId) + "&pageType=" + CAREER_PAGE;
        return http.postJson(url, "{}", headers(site, token), timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode job(TurboHireSite site, String token, String jobId) {
        return http.getJson(baseUrl + "/publicjobs/" + enc(jobId) + "?fieldVisibility=CareerPage",
                headers(site, token), timeoutSeconds, rateLimit());
    }

    private static Map<String, String> headers(TurboHireSite site, String token) {
        return Map.of("Referer", site.referer(), "Authorization", "Bearer " + token);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
