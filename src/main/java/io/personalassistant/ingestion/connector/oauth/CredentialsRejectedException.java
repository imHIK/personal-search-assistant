package io.personalassistant.ingestion.connector.oauth;

/**
 * The provider has refused the grant permanently: the refresh token is revoked, expired or belongs to
 * another client. Distinct from an ordinary {@code 5xx} or {@code 429} because the distinction is the
 * whole point — a transient failure should be retried, while this one will fail identically forever
 * and needs a human to re-consent.
 *
 * <p>{@link OAuthTokenService} reacts to it by dropping the cached token and marking the connection
 * {@code ERROR} immediately, rather than leaving the 30-minute health sweep to notice. That is what
 * turns "my data quietly stopped updating" into a visible prompt to reconnect.
 */
public class CredentialsRejectedException extends RuntimeException {

    public CredentialsRejectedException(String message) {
        super(message);
    }

    public CredentialsRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
