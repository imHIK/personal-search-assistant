package io.personalassistant.ingestion.connector.ats.ashby;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link AshbyApi} against the public posting API (no auth). */
@ApplicationScoped
public class HttpAshbyApi implements AshbyApi {

    @ConfigProperty(name = "app.ingestion.ashby.base-url",
            defaultValue = "https://api.ashbyhq.com/posting-api/job-board")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.ashby.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Override
    public JsonNode listJobs(String boardName) {
        // includeCompensation asks Ashby for structured pay bands, which are far more reliable than
        // scraping a range out of the description prose.
        String url = baseUrl + "/" + encode(boardName) + "?includeCompensation=true";
        return new AtsHttp(timeoutSeconds).getJson(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
