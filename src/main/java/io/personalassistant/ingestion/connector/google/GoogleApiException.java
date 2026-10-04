package io.personalassistant.ingestion.connector.google;

/**
 * Propagates out of grab so the runner's retry applies: a transient 429 or 5xx is retried, and a hard 401 or
 * 403 eventually parks the cursor FAILED.
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

    /** -1 for a transport-level failure. */
    public int statusCode() {
        return statusCode;
    }

    /** Only fetchOne treats a 404 as an answer; anywhere else it is a fault worth retrying. */
    public boolean isNotFound() {
        return statusCode == 404;
    }
}
