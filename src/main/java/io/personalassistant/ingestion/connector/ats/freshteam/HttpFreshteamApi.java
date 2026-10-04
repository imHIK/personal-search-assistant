package io.personalassistant.ingestion.connector.ats.freshteam;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpFreshteamApi implements FreshteamApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "freshteam";

    @ConfigProperty(name = "app.ingestion.freshteam.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpFreshteamApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobs(FreshteamSite site) {
        return http.getJson(site.jobsUrl(), timeoutSeconds, policies.forBoard(PLATFORM, RateLimitMode.WAIT));
    }
}
