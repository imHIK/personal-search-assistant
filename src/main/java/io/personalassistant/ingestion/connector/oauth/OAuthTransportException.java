package io.personalassistant.ingestion.connector.oauth;

/**
 * An OAuth call failed for a reason that is not the credentials — the provider was down, rate-limited,
 * or answered something unparseable. Separate from {@link CredentialsRejectedException} so the retry
 * machinery can keep treating it as an ordinary transient fault and the connection is left alone.
 */
public class OAuthTransportException extends RuntimeException {

    public OAuthTransportException(String message) {
        super(message);
    }

    public OAuthTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
