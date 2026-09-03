package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link SmartRecruitersApi} against the public postings endpoint (no auth). */
@ApplicationScoped
public class HttpSmartRecruitersApi implements SmartRecruitersApi {

    @ConfigProperty(name = "app.ingestion.smartrecruiters.base-url",
            defaultValue = "https://api.smartrecruiters.com/v1/companies")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.smartrecruiters.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Override
    public JsonNode listPostings(String company, int limit, int offset) {
        String url = baseUrl + "/" + encode(company) + "/postings?limit=" + limit + "&offset=" + offset;
        return new AtsHttp(timeoutSeconds).getJson(url);
    }

    @Override
    public JsonNode posting(String company, String postingId) {
        String url = baseUrl + "/" + encode(company) + "/postings/" + encode(postingId);
        return new AtsHttp(timeoutSeconds).getJson(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
