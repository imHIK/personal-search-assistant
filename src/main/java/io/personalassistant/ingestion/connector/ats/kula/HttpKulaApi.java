package io.personalassistant.ingestion.connector.ats.kula;

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
public class HttpKulaApi implements KulaApi {

    /** Public boards: the platform, not an account, owns the quota. */
    private static final String PLATFORM = "kula";

    /** The careers page asks for 99; a larger page is not known to be honoured. */
    private static final int PAGE_SIZE = 99;

    @ConfigProperty(name = "app.ingestion.kula.base-url", defaultValue = "https://careers.kula.ai/api/internal")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.kula.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public HttpKulaApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listJobPosts(String account, int page) {
        String url = baseUrl + "/ats_job_posts?accountName=" + URLEncoder.encode(account, StandardCharsets.UTF_8)
                + "&page=" + page + "&type=ats_job_post.index&items=" + PAGE_SIZE;
        return http.getJson(url, timeoutSeconds, policies.forBoard(PLATFORM, RateLimitMode.WAIT));
    }
}
