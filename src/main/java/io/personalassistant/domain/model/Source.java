package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.SourceStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.Map;

/**
 * A connected data source (a folder, a mailbox, a workspace). Canonical record
 * lives in the Mongo {@code sources} collection.
 *
 * @param id      stable id, e.g. "src_local_documents"
 * @param type    selects the connector adapter
 * @param name    human-friendly label
 * @param config  connector-specific settings, opaque to the core domain
 * @param status  operational state
 * @param sync    incremental-sync bookkeeping
 */
public record Source(
        String id,
        SourceType type,
        String name,
        Map<String, Object> config,
        SourceStatus status,
        SyncState sync,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Incremental sync watermark and last-run stats.
     *
     * @param cursor opaque watermark (timestamp, change-id, page token…) owned by the connector
     */
    public record SyncState(
            String cursor,
            Instant lastRunAt,
            String lastStatus,
            long documents,
            long failed) {}
}
