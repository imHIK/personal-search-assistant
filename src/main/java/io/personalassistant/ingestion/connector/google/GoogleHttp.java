package io.personalassistant.ingestion.connector.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin JSON/bytes HTTP helper shared by the Gmail and Drive REST adapters. It centralises the three
 * things every Google call needs — a bearer header, a request timeout, and non-2xx → {@link
 * GoogleApiException} translation — so the adapters read as a list of endpoints rather than a pile
 * of boilerplate. It is intentionally <em>not</em> a CDI bean: it is a value-like collaborator each
 * adapter constructs with its own base URL and timeout.
 */
public final class GoogleHttp {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    public GoogleHttp(long timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /** GET a URL and parse the body as JSON. */
    public JsonNode getJson(String url, String bearer) {
        HttpResponse<String> response = send(request(url, bearer)
                .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(), url);
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException("Failed to parse JSON from " + url, e);
        }
    }

    /** GET a URL and return the raw bytes (file download / export). */
    public byte[] getBytes(String url, String bearer) {
        return send(request(url, bearer).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(), url).body();
    }

    private HttpRequest.Builder request(String url, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
        if (bearer != null && !bearer.isBlank()) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, String url) {
        try {
            HttpResponse<T> response = http.send(request, handler);
            if (response.statusCode() / 100 != 2) {
                throw new GoogleApiException(response.statusCode(),
                        "Google API " + response.statusCode() + " for " + url + ": "
                                + snippet(response.body()));
            }
            return response;
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Google API request failed for " + url, e);
        }
    }

    private static String snippet(Object body) {
        if (body == null) {
            return "";
        }
        String s = body instanceof byte[] bytes ? new String(bytes) : body.toString();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
