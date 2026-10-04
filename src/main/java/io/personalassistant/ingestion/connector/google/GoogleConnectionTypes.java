package io.personalassistant.ingestion.connector.google;

import io.personalassistant.domain.model.enums.SourceType;

public final class GoogleConnectionTypes {

    public static final String GMAIL = SourceType.GMAIL.name();

    public static final String GOOGLE_DRIVE = SourceType.GOOGLE_DRIVE.name();

    /**
     * A type of its own rather than a scope on {@link #GMAIL}: the credential that reads can never send, and
     * the one that sends can never read.
     */
    public static final String GMAIL_SEND = "GMAIL_SEND";

    private GoogleConnectionTypes() {
    }
}
