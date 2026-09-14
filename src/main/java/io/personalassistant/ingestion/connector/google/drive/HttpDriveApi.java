package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
import io.personalassistant.ingestion.connector.google.GoogleHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link DriveApi} backed by the Google Drive REST API v3 ({@code www.googleapis.com/drive/v3}).
 * This adapter is pure transport + URL building: {@code files.list} always requests the field subset
 * the connector maps from, and enables shared-drive traversal so items in shared drives are visible.
 * No pagination or content policy lives here — that is the connector's job.
 */
@ApplicationScoped
public class HttpDriveApi implements DriveApi {

    private static final String FILE_FIELDS =
            "id,name,mimeType,modifiedTime,size,version,md5Checksum,webViewLink,trashed";
    private static final String LIST_FIELDS = "nextPageToken,files(" + FILE_FIELDS + ")";

    @ConfigProperty(name = "app.ingestion.google-drive.base-url",
            defaultValue = "https://www.googleapis.com/drive/v3")
    String baseUrl;

    @ConfigProperty(name = "app.ingestion.google-drive.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final GoogleHttp http;

    @Inject
    public HttpDriveApi(GoogleHttp http) {
        this.http = http;
    }

    private String base() {
        return baseUrl.replaceAll("/+$", "");
    }

    @Override
    public JsonNode listFiles(GoogleAuth auth, String query, String orderBy, String pageToken, int pageSize) {
        StringJoiner q = new StringJoiner("&", base() + "/files?", "");
        q.add("q=" + enc(query));
        q.add("fields=" + enc(LIST_FIELDS));
        q.add("pageSize=" + Math.min(1000, Math.max(1, pageSize)));
        q.add("supportsAllDrives=true");
        q.add("includeItemsFromAllDrives=true");
        q.add("corpora=allDrives");
        q.add("spaces=drive");
        if (orderBy != null && !orderBy.isBlank()) {
            q.add("orderBy=" + enc(orderBy));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            q.add("pageToken=" + enc(pageToken));
        }
        return http.getJson(q.toString(), auth, timeoutSeconds);
    }

    @Override
    public String fileName(GoogleAuth auth, String fileId) {
        JsonNode node = http.getJson(base() + "/files/" + enc(fileId)
                + "?fields=name&supportsAllDrives=true", auth, timeoutSeconds);
        JsonNode name = node == null ? null : node.get("name");
        return name == null || name.isNull() ? null : name.asText();
    }

    @Override
    public JsonNode getFile(GoogleAuth auth, String fileId) {
        return http.getJson(base() + "/files/" + enc(fileId) + "?fields=" + enc(FILE_FIELDS)
                + "&supportsAllDrives=true", auth, timeoutSeconds);
    }

    @Override
    public byte[] download(GoogleAuth auth, String fileId) {
        return http.getBytes(base() + "/files/" + enc(fileId)
                + "?alt=media&supportsAllDrives=true", auth, timeoutSeconds);
    }

    @Override
    public byte[] export(GoogleAuth auth, String fileId, String exportMimeType) {
        return http.getBytes(base() + "/files/" + enc(fileId)
                + "/export?mimeType=" + enc(exportMimeType), auth, timeoutSeconds);
    }

    @Override
    public JsonNode about(GoogleAuth auth) {
        return http.getJson(base() + "/about?fields=user", auth, timeoutSeconds);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
