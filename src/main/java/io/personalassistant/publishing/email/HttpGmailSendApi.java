package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
import io.personalassistant.ingestion.connector.google.GoogleHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@link GmailSendApi} over the Gmail REST API v1 and Google's OAuth tokeninfo endpoint. */
@ApplicationScoped
public class HttpGmailSendApi implements GmailSendApi {

    @ConfigProperty(name = "app.publishing.gmail.base-url", defaultValue = "https://gmail.googleapis.com/gmail/v1")
    String baseUrl;

    @ConfigProperty(name = "app.publishing.gmail.tokeninfo-url",
            defaultValue = "https://oauth2.googleapis.com/tokeninfo")
    String tokenInfoUrl;

    @ConfigProperty(name = "app.publishing.gmail.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final GoogleHttp http;

    @Inject
    public HttpGmailSendApi(GoogleHttp http) {
        this.http = http;
    }

    @Override
    public JsonNode send(GoogleAuth auth, String rawBase64Url) {
        // base64url's alphabet needs no JSON escaping, so the body is assembled rather than serialised.
        return http.postJson(baseUrl.replaceAll("/+$", "") + "/users/me/messages/send",
                "{\"raw\":\"" + rawBase64Url + "\"}", auth, timeoutSeconds);
    }

    @Override
    public JsonNode tokenInfo(String accessToken) {
        // In the body, not the query string: a bearer token in a URL ends up in access logs.
        return http.postForm(tokenInfoUrl,
                "access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8), timeoutSeconds);
    }
}
