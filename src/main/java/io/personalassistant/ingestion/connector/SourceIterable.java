package io.personalassistant.ingestion.connector;

import java.util.Map;

/**
 * A sub-stream paged independently, with its own cursors.
 *
 * @param iterableId stable, unique within the knowledge
 */
public record SourceIterable(String iterableId, String displayName, Map<String, Object> attributes) {
}
