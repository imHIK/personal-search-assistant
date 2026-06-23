package io.personalassistant.ingestion.connector;

import java.util.Map;

/**
 * A sub-stream within a knowledge that is paged independently — a Slack channel, a Drive
 * folder, a Gmail label, or (for the local filesystem) a directory. Each iterable gets its own
 * cursors so progress and concurrency are tracked per stream rather than per source.
 *
 * @param iterableId  stable identifier of the sub-stream, unique within the knowledge
 * @param displayName human-friendly label
 * @param attributes  connector-specific details (e.g. the absolute folder path), opaque to core
 */
public record SourceIterable(String iterableId, String displayName, Map<String, Object> attributes) {
}
