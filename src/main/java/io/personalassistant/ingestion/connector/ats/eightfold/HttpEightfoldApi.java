package io.personalassistant.ingestion.connector.ats.eightfold;

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
public class HttpEightfoldApi implements EightfoldApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "eightfold";

    @ConfigProperty(name = "app.ingestion.eightfold.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpEightfoldApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listPositions(EightfoldSite site, int start) {
        return get(site, "/api/apply/v2/jobs?domain=" + encode(site.domain()) + "&start=" + start + "&num=10");
    }

    @Override
    public JsonNode position(EightfoldSite site, String id) {
        return get(site, "/api/apply/v2/jobs/" + encode(id) + "?domain=" + encode(site.domain()));
    }

    @Override
    public JsonNode search(EightfoldSite site, String location, int start) {
        return get(site, "/api/pcsx/search?domain=" + encode(site.domain()) + "&query=&location="
                + encode(location == null ? "" : location) + "&start=" + start);
    }

    @Override
    public JsonNode positionDetails(EightfoldSite site, String id) {
        return get(site, "/api/pcsx/position_details?position_id=" + encode(id) + "&domain="
                + encode(site.domain()) + "&hl=en");
    }

    private JsonNode get(EightfoldSite site, String pathAndQuery) {
        return http.getJson("https://" + site.host() + pathAndQuery, timeoutSeconds, rateLimit());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
