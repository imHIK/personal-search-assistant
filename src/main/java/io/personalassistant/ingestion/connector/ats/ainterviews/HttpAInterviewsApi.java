package io.personalassistant.ingestion.connector.ats.ainterviews;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpAInterviewsApi implements AInterviewsApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "ainterviews";

    @ConfigProperty(name = "app.ingestion.ainterviews.base-url",
            defaultValue = "https://ainterviews.com/api/job_board")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.ainterviews.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpAInterviewsApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(String board) {
        String url = baseUrl + "/" + URLEncoder.encode(board, StandardCharsets.UTF_8) + "/jobs/";
        return http.getJson(url, timeoutSeconds, policies.forBoard(PLATFORM, RateLimitMode.WAIT));
    }
}
