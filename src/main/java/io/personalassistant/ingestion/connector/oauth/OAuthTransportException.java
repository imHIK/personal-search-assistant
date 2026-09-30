package io.personalassistant.ingestion.connector.oauth;

/**
 * A failure that is not the credentials (provider down, rate-limited, garbled reply), so retries treat it as
 * transient and the connection is left alone.
 */
public class OAuthTransportException extends RuntimeException {

    public OAuthTransportException(String message) {
        super(message);
    }

    public OAuthTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
