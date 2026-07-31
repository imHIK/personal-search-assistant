package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Google Drive REST surface the {@link GoogleDriveConnector} depends on — the {@code files.list},
 * {@code files.get} (media download), {@code files.export}, and {@code about} endpoints. Isolating it
 * as a port keeps the connector's folder-tree discovery, pagination, and content-mapping logic
 * unit-testable against an in-memory fake, and confines transport to {@link HttpDriveApi}.
 *
 * <p>Every method takes an already-resolved bearer {@code accessToken}; obtaining/refreshing it is
 * the job of {@link io.personalassistant.ingestion.connector.google.GoogleAccessTokens}.
 */
public interface DriveApi {

    /**
     * {@code files.list}: files/folders matching {@code query}, ordered by {@code orderBy}.
     *
     * @param query    a Drive query expression (e.g. {@code "'root' in parents and trashed=false"})
     * @param orderBy  a Drive sort key (e.g. {@code "modifiedTime"} or {@code "modifiedTime desc"}), or null
     * @param pageToken continuation token from a previous page, or null for the first page
     * @param pageSize soft page-size cap
     * @return node with {@code files[]} (id, name, mimeType, modifiedTime, size, version, md5Checksum,
     *         webViewLink) and an optional {@code nextPageToken}
     */
    JsonNode listFiles(String accessToken, String query, String orderBy, String pageToken, int pageSize);

    /** {@code files.get?alt=media}: raw bytes of a binary (non-Google-native) file. */
    byte[] download(String accessToken, String fileId);

    /** {@code files.export}: a Google-native doc rendered to {@code exportMimeType} (e.g. text/plain). */
    byte[] export(String accessToken, String fileId, String exportMimeType);

    /** {@code about?fields=user}: used by {@code verify} to prove the credentials work. */
    JsonNode about(String accessToken);
}
