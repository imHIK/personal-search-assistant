package io.personalassistant.ingestion.connector.ats.lever;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link LeverApi} against the public postings endpoint (no auth). */
@ApplicationScoped
public class HttpLeverApi implements LeverApi {

    @ConfigProperty(name = "app.ingestion.lever.base-url", defaultValue = "https://api.lever.co/v0/postings")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.lever.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Override
    public JsonNode listPostings(String site) {
        // mode=json returns structured postings rather than rendered HTML pages.
        String url = baseUrl + "/" + encode(site) + "?mode=json";
        return new AtsHttp(timeoutSeconds).getJson(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
