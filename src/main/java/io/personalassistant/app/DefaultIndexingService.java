package io.personalassistant.app;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Document;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.Source;
import io.personalassistant.domain.model.enums.IndexStatus;
import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.indexing.chunking.ChunkingStrategy;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.parser.ContentParser;
import io.personalassistant.ingestion.parser.ParserRegistry;
import io.personalassistant.storage.repository.ChunkRepository;
import io.personalassistant.storage.repository.DocumentRepository;
import io.personalassistant.storage.repository.SourceRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Write-path orchestration: pull from a source, parse, persist canonical document, chunk,
 * embed, persist chunks, and index into the search engine — updating sync state for
 * incremental runs. Synchronous today; the same contract can later enqueue work onto a
 * broker without changing callers.
 */
@ApplicationScoped
public class DefaultIndexingService implements IndexingService {

    private final SourceRepository sources;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ConnectorRegistry connectors;
    private final ParserRegistry parsers;
    private final ChunkingStrategy chunking;
    private final EmbeddingProvider embeddings;
    private final SearchIndex index;

    @Inject
    public DefaultIndexingService(SourceRepository sources,
                                  DocumentRepository documents,
                                  ChunkRepository chunks,
                                  ConnectorRegistry connectors,
                                  ParserRegistry parsers,
                                  ChunkingStrategy chunking,
                                  EmbeddingProvider embeddings,
                                  SearchIndex index) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.connectors = connectors;
        this.parsers = parsers;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.index = index;
    }

    @Override
    public IndexRunResult sync(String sourceId) {
        Source source = sources.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source " + sourceId));
        SourceConnector connector = connectors.get(source.type());
        String cursor = source.sync() == null ? null : source.sync().cursor();

        long processed = 0, skipped = 0, failed = 0;
        try (Stream<RawItem> items = connector.fetch(source, cursor)) {
            for (RawItem item : (Iterable<RawItem>) items::iterator) {
                try {
                    if (indexItem(source, item)) {
                        processed++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                }
            }
        }

        String nextCursor = connector.nextCursor(source, cursor);
        sources.updateSyncState(sourceId,
                new Source.SyncState(nextCursor, Instant.now(), "OK", processed, failed));
        return new IndexRunResult(sourceId, processed, skipped, failed);
    }

    /** @return true if (re)indexed, false if skipped as unchanged. */
    private boolean indexItem(Source source, RawItem item) {
        var existing = documents.findBySourceAndExternalId(source.id(), item.externalId());
        if (existing.isPresent() && item.checksum() != null
                && item.checksum().equals(existing.get().checksum())) {
            return false;
        }

        ContentParser parser = parsers.get(item.contentType());
        ParsedContent parsed = parser.parse(item);
        Instant now = Instant.now();
        String id = existing.map(Document::id).orElse("doc_" + UUID.randomUUID());

        Document doc = new Document(
                id, source.id(), item.externalId(), item.contentType(),
                item.title(), item.uri(), parsed.text(), parsed.metadata(),
                item.checksum(), IndexStatus.PARSING, 0, null,
                existing.map(Document::createdAt).orElse(now), now);
        Document saved = documents.upsert(doc);

        List<Chunk> embedded = embedChunks(chunking.chunk(saved));
        chunks.replaceForDocument(saved.id(), embedded);
        index.indexChunks(embedded);
        documents.updateStatus(saved.id(), IndexStatus.INDEXED, null);
        return true;
    }

    @Override
    public void reindexDocument(String documentId) {
        Document doc = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        List<Chunk> embedded = embedChunks(chunking.chunk(doc));
        chunks.replaceForDocument(doc.id(), embedded);
        index.indexChunks(embedded);
        documents.updateStatus(doc.id(), IndexStatus.INDEXED, null);
    }

    @Override
    public void deleteDocument(String documentId) {
        chunks.deleteByDocument(documentId);
        index.deleteByDocument(documentId);
        documents.delete(documentId);
    }

    private List<Chunk> embedChunks(List<Chunk> raw) {
        List<Embedding> vectors = embeddings.embedAll(raw.stream().map(Chunk::text).toList());
        List<Chunk> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Chunk c = raw.get(i);
            out.add(new Chunk(c.id(), c.documentId(), c.sourceId(), c.ordinal(),
                    c.text(), c.tokenCount(), vectors.get(i), c.metadata()));
        }
        return out;
    }
}
