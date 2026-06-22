package io.personalassistant.domain.model;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Raw, unparsed item emitted by a {@code SourceConnector}. The bytes are supplied
 * lazily so connectors can stream large payloads without holding them in memory.
 *
 * @param externalId  natural key within the source
 * @param contentType MIME type, used to pick a {@code ContentParser}
 * @param uri         locator for citations
 * @param checksum    content hash, if the source can provide one cheaply
 * @param metadata    source-native attributes
 * @param content     lazy supplier of the raw bytes (null for metadata-only items)
 */
public record RawItem(
        String externalId,
        String contentType,
        String title,
        String uri,
        String checksum,
        Instant modifiedAt,
        Map<String, Object> metadata,
        Supplier<InputStream> content) {}
