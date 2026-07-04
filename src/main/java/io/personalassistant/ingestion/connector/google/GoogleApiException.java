package io.personalassistant.ingestion.connector.google;

/**
 * Raised when a Google REST call (Gmail / Drive / OAuth token) returns a non-2xx status or the
 * transport fails. Connectors let this propagate out of {@code grab}; the ingestion runner then
 * records the failure on the cursor and applies its retry/backoff policy — so a transient 429/5xx
 * is retried like any other ingestion error, and a hard 401/403 eventually parks the cursor
 * {@code FAILED} for intervention.
 */
public class GoogleApiException extends RuntimeException {

    private final int statusCode;

    public GoogleApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public GoogleApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP status that triggered this, or {@code -1} for a transport-level failure. */
    public int statusCode() {
        return statusCode;
    }
}
