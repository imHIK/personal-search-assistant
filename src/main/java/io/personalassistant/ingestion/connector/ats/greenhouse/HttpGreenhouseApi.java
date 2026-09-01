package io.personalassistant.ingestion.connector.ats.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link GreenhouseApi} against the public board endpoint (no auth). */
@ApplicationScoped
public class HttpGreenhouseApi implements GreenhouseApi {

    @ConfigProperty(name = "app.ingestion.greenhouse.base-url",
            defaultValue = "https://boards-api.greenhouse.io/v1/boards")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.greenhouse.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Override
    public JsonNode listJobs(String boardToken) {
        // content=true is what makes this one call sufficient: without it Greenhouse returns job
        // stubs and the description needs a second request per posting.
        String url = baseUrl + "/" + encode(boardToken) + "/jobs?content=true";
        return new AtsHttp(timeoutSeconds).getJson(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
