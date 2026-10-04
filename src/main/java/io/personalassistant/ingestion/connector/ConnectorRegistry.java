package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.enums.SourceType;

public interface ConnectorRegistry {

    /** @throws IllegalArgumentException if no connector is registered for the type */
    SourceConnector get(SourceType type);

    boolean supports(SourceType type);
}
