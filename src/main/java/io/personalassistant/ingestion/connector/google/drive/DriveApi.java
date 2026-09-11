package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;

/**
 * The Google Drive REST surface the {@link GoogleDriveConnector} depends on — the {@code files.list},
 * {@code files.get} (media download), {@code files.export}, and {@code about} endpoints. Isolating it
 * as a port keeps the connector's folder-tree discovery, pagination, and content-mapping logic
 * unit-testable against an in-memory fake, and confines transport to {@link HttpDriveApi}.
 *
 * <p>Every method takes already-resolved {@link GoogleAuth} — the bearer token plus the account's rate
 * limit; obtaining, refreshing and resolving both is the job of
 * {@link io.personalassistant.ingestion.connector.google.GoogleAccessTokens}.
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
    JsonNode listFiles(GoogleAuth auth, String query, String orderBy, String pageToken, int pageSize);

    /**
     * {@code files.get?fields=name}: the display name of one file or folder.
     *
     * <p>Exists for the folder ids a user configures by hand. Everything else the connector sees comes
     * from a listing, which carries names already; a configured root does not, and the console was
     * left showing the raw Drive id as the folder's name.
     *
     * @return the name, or null when the id cannot be read — a missing label is not worth failing
     *         discovery over
     */
    String fileName(GoogleAuth auth, String fileId);

    /**
     * {@code files.get}: one file's metadata, in the same field set a listing row carries — so the
     * connector maps it with exactly the code it maps a listing with, and the re-listed item is
     * indistinguishable from the walked one (same checksum rule above all). Unlike the list query,
     * this returns trashed files too, so {@code trashed} is part of that field set.
     *
     * @return the file node, or null when the id no longer resolves
     */
    JsonNode getFile(GoogleAuth auth, String fileId);

    /** {@code files.get?alt=media}: raw bytes of a binary (non-Google-native) file. */
    byte[] download(GoogleAuth auth, String fileId);

    /** {@code files.export}: a Google-native doc rendered to {@code exportMimeType} (e.g. text/plain). */
    byte[] export(GoogleAuth auth, String fileId, String exportMimeType);

    /** {@code about?fields=user}: used by {@code verify} to prove the credentials work. */
    JsonNode about(GoogleAuth auth);
}
