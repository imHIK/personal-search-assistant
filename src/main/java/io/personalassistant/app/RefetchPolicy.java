package io.personalassistant.app;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides whether a re-index has to go back to the source for content, or can run from what the
 * entity already stores.
 *
 * <p>Its own bean because two callers need the same answer — {@code DefaultIndexingService} for the
 * explicit re-index endpoints and {@code DefaultKnowledgeService} for the membership re-walk an edit
 * triggers — and because the decision has two inputs that should not be duplicated: the connector's
 * {@link ReindexMode} and the operator's override.
 *
 * <p>The user is deliberately not one of those inputs. "Re-fetch" is an implementation detail of
 * making an item current again; asking someone to know whether their Drive file still happens to be
 * in a temp directory is asking them to reason about our staging, so the console offers one re-index
 * action and this decides what that costs.
 */
@ApplicationScoped
public class RefetchPolicy {

    /**
     * {@code auto} defers to the connector; {@code always} / {@code never} override it.
     * Package-private, and initialized to the same default the config declares, so a hand-wired unit
     * test gets the shipped behaviour without a config source and can set it directly to vary.
     */
    @ConfigProperty(name = "app.indexing.refetch-on-reindex", defaultValue = "auto")
    String mode = "auto";

    private final ConnectorRegistry connectors;

    @Inject
    public RefetchPolicy(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    /** Whether this knowledge's file-backed entities should be re-fetched before re-indexing. */
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
     * Whether this specific entity should be re-fetched. Inline text is never re-fetched however the
     * policy is set: it lives in Mongo alongside the entity, so there is nothing a fetch would
     * recover and a Drive knowledge of exported native docs would pay an export call per item for
     * content it already holds.
     */
    public boolean refetches(Knowledge knowledge, Entity entity) {
        return entity.content() != null && entity.content().isFile() && refetches(knowledge);
    }
}
