package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;

public interface DriveApi {

    JsonNode listFiles(GoogleAuth auth, String query, String orderBy, String pageToken, int pageSize);

    /**
     * For configured root ids, which no listing names.
     *
     * @return null when the id cannot be read
     */
    String fileName(GoogleAuth auth, String fileId);

    /**
     * The same field set as a listing row, so it maps identically. Unlike the list query, it returns trashed
     * files too.
     *
     * @return null when the id no longer resolves
     */
    JsonNode getFile(GoogleAuth auth, String fileId);

    byte[] download(GoogleAuth auth, String fileId);

    byte[] export(GoogleAuth auth, String fileId, String exportMimeType);

    JsonNode about(GoogleAuth auth);
}
