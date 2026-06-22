package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.Source;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.stream.Stream;

/**
 * THE primary extension point for new integrations. One implementation per data source
 * (local FS, Gmail, Slack…). Implementations live in adapter packages and register
 * themselves with the {@link ConnectorRegistry}.
 *
 * <p>Contract: given a source and its last cursor, stream the items that are new or
 * changed, and report the new cursor so the next run is incremental.
 */
public interface SourceConnector {

    /** Which source type this connector handles; used by the registry to select it. */
    SourceType type();

    /** Validate connectivity/credentials for a configured source. */
    void verify(Source source);

    /**
     * Stream new/changed items since {@code cursor}. The stream is lazy so large sources
     * can be processed incrementally; callers must close it.
     *
     * @param source the configured source (provides {@code config})
     * @param cursor opaque watermark from the previous run, or null for a full sync
     */
    Stream<RawItem> fetch(Source source, String cursor);

    /**
     * The cursor to persist after a successful run over the items just fetched.
     * Kept separate from {@link #fetch} so connectors can compute it from a final state
     * (e.g. server-side delta token) rather than per-item.
     */
    String nextCursor(Source source, String previousCursor);
}
