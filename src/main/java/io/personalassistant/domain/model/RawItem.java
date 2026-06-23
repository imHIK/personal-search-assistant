package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.Map;

/**
 * A raw item emitted by a {@code SourceConnector} grabber for one page. It is mapped into a
 * canonical {@link Entity} at persistence time. Per the indexing design, file bytes never
 * travel inside the item: files are referenced by {@link #fileRef} (an absolute local path)
 * and read directly at indexing time; small text payloads may be carried inline in
 * {@link #text}.
 *
 * @param externalId  natural key within the source (path, message id…)
 * @param entityType  coarse classification
 * @param contentType MIME type, used to pick a {@code ContentParser}
 * @param title       display title
 * @param uri         citation locator
 * @param checksum    content hash, if the source can provide one cheaply
 * @param modifiedAt  source-side last-modified time, if known
 * @param raw         complete source payload, retained for controllable re-indexing
 * @param text        inline extracted text for text items, or null
 * @param fileRef     absolute local filesystem path for file items, or null
 * @param metadata    normalized + source-native attributes
 * @param deleted     tombstone flag: the item was removed at the source
 */
public record RawItem(
        String externalId,
        EntityType entityType,
        String contentType,
        String title,
        String uri,
        String checksum,
        Instant modifiedAt,
        Map<String, Object> raw,
        String text,
        String fileRef,
        Map<String, Object> metadata,
        boolean deleted) {

    /** Builder-free convenience for a live (non-tombstone) file item. */
    public static RawItem file(String externalId, String contentType, String title, String uri,
                               String checksum, Instant modifiedAt, String fileRef,
                               Map<String, Object> raw, Map<String, Object> metadata) {
        return new RawItem(externalId, EntityType.FILE, contentType, title, uri, checksum,
                modifiedAt, raw, null, fileRef, metadata, false);
    }

    /** Convenience for a tombstone (deletion) of a previously ingested item. */
    public static RawItem tombstone(String externalId) {
        return new RawItem(externalId, EntityType.OTHER, null, null, null, null, null,
                Map.of(), null, null, Map.of(), true);
    }
}
