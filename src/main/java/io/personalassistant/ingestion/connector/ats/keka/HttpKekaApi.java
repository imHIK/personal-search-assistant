package io.personalassistant.ingestion.connector.ats.keka;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpKekaApi implements KekaApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "keka";

    @ConfigProperty(name = "app.ingestion.keka.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpKekaApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public String careersPage(KekaSite site) {
        return http.getText(site.careersPage(), timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode listJobs(KekaSite site, String boardId) {
        return http.getJson(site.jobsUrl(boardId), timeoutSeconds, rateLimit());
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
