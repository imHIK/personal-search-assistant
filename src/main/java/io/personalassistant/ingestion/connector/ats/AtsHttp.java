package io.personalassistant.ingestion.connector.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin JSON-over-HTTP helper shared by the ATS board adapters, mirroring
 * {@code google.GoogleHttp} — a request timeout plus non-2xx &rarr; {@link AtsApiException}
 * translation, so each adapter reads as a list of endpoints.
 *
 * <p>Unlike the Google helper there is no bearer header: these are the boards' <em>public</em>
 * job-board endpoints, which is also why the connectors need no {@code Connection}.
 *
 * <p>Intentionally not a CDI bean — a value-like collaborator each adapter constructs with its own
 * base URL and timeout.
 */
public final class AtsHttp {

    private static final int SNIPPET_CHARS = 300;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    public AtsHttp(long timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /** GET a URL and parse the body as JSON. */
    public JsonNode getJson(String url) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build(), url);
    }

    /**
     * POST a JSON body and parse the reply as JSON.
     *
     * <p>Here for Workday, whose job search is a POST with the paging window in the body rather than a
     * query string. Everything else about the call — timeout, non-2xx translation — is identical.
     */
    public JsonNode postJson(String url, String body) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), url);
    }

    private JsonNode send(HttpRequest request, String url) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtsApiException("Interrupted calling " + url, e);
        } catch (Exception e) {
            throw new AtsApiException("ATS request failed for " + url, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new AtsApiException(response.statusCode(),
                    "ATS API " + response.statusCode() + " for " + url + ": " + snippet(response.body()));
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new AtsApiException("Failed to parse JSON from " + url, e);
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= SNIPPET_CHARS ? body : body.substring(0, SNIPPET_CHARS) + "…";
    }
}
