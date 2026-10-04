package io.personalassistant.ingestion.connector.ats;

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

    /** 0 when the call failed before a response arrived. */
    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
