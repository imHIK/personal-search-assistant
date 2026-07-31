package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleHttp;
import jakarta.enterprise.context.ApplicationScoped;
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

    private volatile GoogleHttp http;

    private GoogleHttp http() {
        GoogleHttp local = http;
        if (local == null) {
            local = new GoogleHttp(timeoutSeconds);
            http = local;
        }
        return local;
    }

    private String base() {
        return baseUrl.replaceAll("/+$", "");
    }

    @Override
    public JsonNode listFiles(String accessToken, String query, String orderBy, String pageToken, int pageSize) {
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
        return http().getJson(q.toString(), accessToken);
    }

    @Override
    public byte[] download(String accessToken, String fileId) {
        return http().getBytes(base() + "/files/" + enc(fileId)
                + "?alt=media&supportsAllDrives=true", accessToken);
    }

    @Override
    public byte[] export(String accessToken, String fileId, String exportMimeType) {
        return http().getBytes(base() + "/files/" + enc(fileId)
                + "/export?mimeType=" + enc(exportMimeType), accessToken);
    }

    @Override
    public JsonNode about(String accessToken) {
        return http().getJson(base() + "/about?fields=user", accessToken);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
