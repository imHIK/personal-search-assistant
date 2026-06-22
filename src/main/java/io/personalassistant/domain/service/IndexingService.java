package io.personalassistant.domain.service;

/**
 * Orchestrates the write path: pull from a source, parse, persist, chunk, embed, index.
 * <p>This is a use-case port. The initial implementation runs synchronously; a later
 * implementation can push work onto a queue (Kafka/RabbitMQ) without changing this
 * contract.
 */
public interface IndexingService {

    /**
     * Run an incremental sync for a single source: fetch new/changed items since the
     * last cursor and index them.
     *
     * @param sourceId the source to sync
     * @return a summary of the run
     */
    IndexRunResult sync(String sourceId);

    /** Re-index a single document from its source of truth (e.g. after a parser change). */
    void reindexDocument(String documentId);

    /** Remove a document and all its chunks from Mongo and the search index. */
    void deleteDocument(String documentId);

    /**
     * @param sourceId   the source synced
     * @param processed  items successfully indexed
     * @param skipped    items unchanged (checksum match)
     * @param failed     items that errored
     */
    record IndexRunResult(String sourceId, long processed, long skipped, long failed) {}
}
