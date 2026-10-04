package io.personalassistant.ingestion.connector.ats.zwayam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class HttpZwayamApi implements ZwayamApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "zwayam";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "app.ingestion.zwayam.base-url", defaultValue = "https://public.zwayam.com")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.zwayam.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpZwayamApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode search(String domain, int offset) {
        ObjectNode criteria = MAPPER.createObjectNode()
                .put("paginationStartNo", offset)
                .put("selectedCall", "sort")
                .put("anyOfTheseWords", "");
        criteria.putObject("sortCriteria").put("name", "modifiedDate").put("isAscending", false);
        return http.postForm(baseUrl + "/jobs/search",
                Map.of("filterCri", criteria.toString(), "domain", domain), timeoutSeconds, rateLimit());
    }

    @Override
    public JsonNode job(String companyId, String jobUrl) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("jobUrl", jobUrl)
                .put("externalSource", "CAREERSITE")
                .put("campusUrl", "empty")
                .put("companyId", companyId);
        return http.postJson(baseUrl + "/jobs-service/v1/jobs/careersite", body.toString(), timeoutSeconds,
                rateLimit());
    }

    @Override
    public JsonNode careerSite(String companyId) {
        String url = baseUrl + "/data-service/v2/company/" + URLEncoder.encode(companyId, StandardCharsets.UTF_8)
                + "/careersite-configurations";
        return http.getJson(url, timeoutSeconds, rateLimit());
    }

    private RateLimit rateLimit() {
        return policies.forBoard(PLATFORM, RateLimitMode.WAIT);
    }
}
