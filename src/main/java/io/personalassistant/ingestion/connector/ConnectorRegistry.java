package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.enums.SourceType;

/**
 * Resolves the right {@link SourceConnector} for a source type at runtime. New connectors
 * register here (e.g. via CDI discovery) so adding an integration needs no edits to a
 * central switch statement.
 */
public interface ConnectorRegistry {

    /** @throws IllegalArgumentException if no connector is registered for the type */
    SourceConnector get(SourceType type);

    boolean supports(SourceType type);
}
