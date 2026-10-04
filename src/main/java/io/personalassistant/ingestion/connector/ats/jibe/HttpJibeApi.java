package io.personalassistant.ingestion.connector.ats.jibe;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpJibeApi implements JibeApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "jibe";

    @ConfigProperty(name = "app.ingestion.jibe.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpJibeApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(JibeSite site, int page, int limit) {
        String url = site.apiRoot() + "?page=" + page + "&limit=" + limit;
        return http.getJson(url, timeoutSeconds, policies.forBoard(PLATFORM, RateLimitMode.WAIT));
    }
}
