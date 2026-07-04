package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;

/**
 * Resolves the {@link Connection} a knowledge should authenticate through — the generic seam between
 * a knowledge and the reusable, per-account credentials it uses. Resolution is: the connection named
 * by {@code knowledge.connectorDetails().connectionId()} if set, otherwise the default connection for
 * the knowledge's {@link io.personalassistant.domain.model.enums.SourceType}.
 *
 * <p>Connectors that {@link SourceConnector#requiresConnection() need credentials} call this in
 * {@code discover}/{@code grab}/{@code verify} instead of reading auth off the knowledge, so the
 * whole framework (not just Google connectors) shares one resolution rule and one place to evolve it
 * (e.g. a per-user secret store). Kept as an interface so tests can supply a trivial stub.
 */
public interface ConnectionResolver {

    /**
     * @return the resolved, usable connection for {@code knowledge}
     * @throws java.util.NoSuchElementException if a named connection id does not exist, or no default
     *                                          connection is configured for the knowledge's type
     */
    Connection resolve(Knowledge knowledge);
}
