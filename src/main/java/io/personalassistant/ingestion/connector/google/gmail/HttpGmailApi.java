package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
import io.personalassistant.ingestion.connector.google.GoogleHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link GmailApi} backed by the Gmail REST API v1 ({@code gmail.googleapis.com}). All calls target
 * the authenticated user ({@code users/me}). This adapter is pure transport + URL building: no
 * pagination or mapping policy lives here — that is the connector's job.
 */
@ApplicationScoped
public class HttpGmailApi implements GmailApi {

    @ConfigProperty(name = "app.ingestion.gmail.base-url",
            defaultValue = "https://gmail.googleapis.com/gmail/v1")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.gmail.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final GoogleHttp http;

    @Inject
    public HttpGmailApi(GoogleHttp http) {
        this.http = http;
    }

    private String base() {
        return baseUrl.replaceAll("/+$", "") + "/users/me";
    }

    @Override
    public JsonNode listMessages(GoogleAuth auth, List<String> labelIds, String query,
                                 String pageToken, int maxResults) {
        StringJoiner q = new StringJoiner("&", base() + "/messages?", "");
        q.add("maxResults=" + Math.max(1, maxResults));
        if (labelIds != null) {
            for (String label : labelIds) {
                q.add("labelIds=" + enc(label));
            }
        }
        if (query != null && !query.isBlank()) {
            q.add("q=" + enc(query));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            q.add("pageToken=" + enc(pageToken));
        }
        return http.getJson(q.toString(), auth, timeoutSeconds);
    }

    @Override
    public JsonNode getMessage(GoogleAuth auth, String id) {
        return http.getJson(base() + "/messages/" + enc(id) + "?format=full", auth, timeoutSeconds);
    }

    @Override
    public JsonNode listLabels(GoogleAuth auth) {
        return http.getJson(base() + "/labels", auth, timeoutSeconds);
    }

    @Override
    public JsonNode getProfile(GoogleAuth auth) {
        return http.getJson(base() + "/profile", auth, timeoutSeconds);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
