package io.personalassistant.ingestion.connector.oauth;

/**
 * The grant is permanently refused (revoked, expired or foreign): unlike a transient failure, it needs a
 * human to re-consent. OAuthTokenService marks the connection ERROR at once.
 */
public class CredentialsRejectedException extends RuntimeException {

    public CredentialsRejectedException(String message) {
        super(message);
    }

    public CredentialsRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
