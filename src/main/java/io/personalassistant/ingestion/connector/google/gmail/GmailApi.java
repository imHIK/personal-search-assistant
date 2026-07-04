package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The Gmail REST surface the {@link GmailConnector} depends on — a narrow seam over the four
 * endpoints the connector actually uses. Isolating it as a port keeps the connector's pagination and
 * mapping logic unit-testable against an in-memory fake (mirroring how {@code LocalFsConnectorTest}
 * runs against a real temp dir), and confines all transport concerns to {@link HttpGmailApi}.
 *
 * <p>Every method takes an already-resolved bearer {@code accessToken}; obtaining/refreshing it is
 * the job of {@link io.personalassistant.ingestion.connector.google.GoogleAccessTokens}.
 */
public interface GmailApi {

    /**
     * {@code users.messages.list}: message ids matching {@code query} within {@code labelIds},
     * newest first. The returned node carries {@code messages[].id} and an optional
     * {@code nextPageToken}.
     *
     * @param labelIds restrict to these labels (empty = all mail)
     * @param query    a Gmail search expression (e.g. {@code "after:169..."}), or null
     * @param pageToken continuation token from a previous page, or null for the first page
     * @param maxResults soft page-size cap
     */
    JsonNode listMessages(String accessToken, List<String> labelIds, String query,
                          String pageToken, int maxResults);

    /** {@code users.messages.get} in {@code full} format: headers + payload parts + internalDate. */
    JsonNode getMessage(String accessToken, String id);

    /** {@code users.labels.list}: all labels ({@code labels[].id}, {@code labels[].name}, type). */
    JsonNode listLabels(String accessToken);

    /** {@code users.getProfile}: used by {@code verify} to prove the credentials work. */
    JsonNode getProfile(String accessToken);
}
