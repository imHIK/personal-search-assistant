package io.personalassistant.testsupport;

import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;

/** A {@link ConnectorRegistry} backed by a single connector, for tests. */
public class SingleConnectorRegistry implements ConnectorRegistry {

    private final SourceConnector connector;

    public SingleConnectorRegistry(SourceConnector connector) {
        this.connector = connector;
    }

    @Override
    public SourceConnector get(SourceType type) {
        if (connector.type() != type) {
            throw new IllegalArgumentException("No connector for " + type);
        }
        return connector;
    }

    @Override
    public boolean supports(SourceType type) {
        return connector.type() == type;
    }
}
