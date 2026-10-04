package io.personalassistant.app;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether a re-index goes back to the source: the connector's ReindexMode, overridable by
 * {@code app.indexing.refetch-on-reindex}. Deliberately never the user's choice.
 */
@ApplicationScoped
public class RefetchPolicy {

    /** {@code auto} defers to the connector; {@code always} and {@code never} override it. */
    @ConfigProperty(name = "app.indexing.refetch-on-reindex", defaultValue = "auto")
    String mode = "auto";

    private final ConnectorRegistry connectors;

    @Inject
    public RefetchPolicy(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    public boolean refetches(Knowledge knowledge) {
        if ("never".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("always".equalsIgnoreCase(mode)) {
            return true;
        }
        return connectors.get(knowledge.connectorDetails().type()).defaultReindexMode()
                == ReindexMode.FETCH_AND_REINDEX;
    }

    /**
     * Inline text is never re-fetched: it already lives in Mongo, and a fetch would only cost an export call.
     */
    public boolean refetches(Knowledge knowledge, Entity entity) {
        return entity.content() != null && entity.content().isFile() && refetches(knowledge);
    }
}
