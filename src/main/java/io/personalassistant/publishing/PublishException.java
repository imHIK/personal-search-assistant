package io.personalassistant.publishing;

/**
 * Classified by whether retrying could help: a transient failure is retried with backoff; a permanent one
 * parks the channel in ERROR, so its other deliveries stop spending attempts on the same refusal.
 */
public class PublishException extends RuntimeException {

    private final boolean permanent;

    public PublishException(boolean permanent, String message, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public static PublishException permanent(String message, Throwable cause) {
        return new PublishException(true, message, cause);
    }

    public static PublishException transientFailure(String message, Throwable cause) {
        return new PublishException(false, message, cause);
    }

    public boolean permanent() {
        return permanent;
    }
}
