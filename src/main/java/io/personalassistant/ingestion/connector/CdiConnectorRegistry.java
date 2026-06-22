package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;

/**
 * Discovers all {@link SourceConnector} beans via CDI and indexes them by type. Adding a
 * new connector = adding a new bean; no edits here. Empty today (no connectors yet).
 */
@ApplicationScoped
public class CdiConnectorRegistry implements ConnectorRegistry {

    private final Map<SourceType, SourceConnector> byType = new EnumMap<>(SourceType.class);

    @Inject
    public CdiConnectorRegistry(Instance<SourceConnector> connectors) {
        for (SourceConnector c : connectors) {
            byType.put(c.type(), c);
        }
    }

    @Override
    public SourceConnector get(SourceType type) {
        SourceConnector c = byType.get(type);
        if (c == null) {
            throw new IllegalArgumentException("No connector registered for source type " + type);
        }
        return c;
    }

    @Override
    public boolean supports(SourceType type) {
        return byType.containsKey(type);
    }
}
