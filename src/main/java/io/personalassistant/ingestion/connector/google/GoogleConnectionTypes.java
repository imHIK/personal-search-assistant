package io.personalassistant.ingestion.connector.google;

import io.personalassistant.domain.model.enums.SourceType;

/**
 * The connection types a Google account can be connected as, in one place beside the provider that maps
 * each to its scopes ({@link GoogleOAuthProvider}).
 */
public final class GoogleConnectionTypes {

    /** The Gmail connector's read-only account. */
    public static final String GMAIL = SourceType.GMAIL.name();

    /** The Drive connector's read-only account. */
    public static final String GOOGLE_DRIVE = SourceType.GOOGLE_DRIVE.name();

    /**
     * A send-only Gmail account for the email publisher. Deliberately a type of its own rather than an
     * extra scope on {@link #GMAIL}: the credential ingestion reads with can never send, and the one that
     * sends can never read — a leak of either costs only what that one is for.
     */
    public static final String GMAIL_SEND = "GMAIL_SEND";

    private GoogleConnectionTypes() {
    }
}
