package io.personalassistant.ingestion.connector.ats;

/**
 * Failure talking to an applicant-tracking board API. Carries the HTTP status when there was one so
 * callers can distinguish "this board token does not exist" (404 during {@code verify}) from a
 * transient outage, which the ingestion retry/backoff path should simply re-attempt.
 */
public class AtsApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public AtsApiException(String message) {
        super(message);
        this.status = 0;
    }

    public AtsApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public AtsApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status, or {@code 0} when the call failed before a response arrived. */
    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
