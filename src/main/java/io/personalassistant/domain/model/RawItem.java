package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.Map;

/**
 * One item from a connector's grab. File bytes never travel in it: a file is a fileRef read at indexing time.
 *
 * @param checksum must change whenever the item changes; it is the only change signal
 * @param expiresAt set only when the source states an end date
 * @param deleted a tombstone: the item was removed at the source
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
        Instant expiresAt,
        boolean deleted) {

    public static RawItem file(String externalId, String contentType, String title, String uri,
                               String checksum, Instant modifiedAt, String fileRef,
                               Map<String, Object> raw, Map<String, Object> metadata) {
        return new RawItem(externalId, EntityType.FILE, contentType, title, uri, checksum,
                modifiedAt, raw, null, fileRef, metadata, null, false);
    }

    public static RawItem tombstone(String externalId) {
        return new RawItem(externalId, EntityType.OTHER, null, null, null, null, null,
                Map.of(), null, null, Map.of(), null, true);
    }
}
