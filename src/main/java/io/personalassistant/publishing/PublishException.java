package io.personalassistant.publishing;

/**
 * A send that did not happen, classified by whether trying again could help.
 *
 * <p>The split mirrors {@code CredentialsRejectedException} versus {@code OAuthTransportException} on
 * the connector side, for the same reason. A <em>transient</em> failure (timeout, a 4xx SMTP reply, a
 * refused connection) is retried with backoff and is nobody's fault. A <em>permanent</em> one (rejected
 * credentials, a recipient the server refuses) will fail identically on every attempt, so it parks the
 * channel in {@code ERROR} — which stops every other message queued for it from burning its attempts on
 * the same refusal — and waits for a person.
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
