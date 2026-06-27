package io.personalassistant.indexing.job;

import io.personalassistant.common.Errors;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.indexing.chunking.ChunkingStrategy;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import io.personalassistant.indexing.parser.ParserRegistry;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Transforms one claimed entity into search-index chunks: extract text (Tika for files, inline
 * for text), chunk (global config), embed (batched), then replace the entity's chunks in
 * OpenSearch and record what was written on the entity. Tombstoned entities have their chunks
 * removed instead. Extracted from {@link IndexingJob} so the per-entity logic is unit-testable
 * with in-memory fakes.
 *
 * <p>Because the entity retains its {@code raw}/{@code fileRef}, a re-index (new chunking config
 * or embedding model) is just this path run again — no source calls.
 */
@ApplicationScoped
public class IndexingRunner {

    private static final Logger LOG = Logger.getLogger(IndexingRunner.class.getName());

    private final EntityRepository entities;
    private final KnowledgeRepository knowledge;
    private final ParserRegistry parsers;
    private final ChunkingStrategy chunking;
    private final EmbeddingProvider embeddings;
    private final SearchIndex index;

    @ConfigProperty(name = "app.indexing.embed-batch", defaultValue = "64")
    int embedBatch;

    @ConfigProperty(name = "app.indexing.retry-limit", defaultValue = "5")
    int retryLimit;

    @ConfigProperty(name = "app.indexing.backoff-seconds", defaultValue = "30")
    long backoffSeconds;

    @ConfigProperty(name = "app.indexing.lease-seconds", defaultValue = "120")
    long leaseSeconds;

    @Inject
    public IndexingRunner(EntityRepository entities, KnowledgeRepository knowledge,
                          ParserRegistry parsers, ChunkingStrategy chunking,
                          EmbeddingProvider embeddings, SearchIndex index) {
        this.entities = entities;
        this.knowledge = knowledge;
        this.parsers = parsers;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.index = index;
    }

    /** Index (or re-index) a single entity. Catches and records failures with retry/backoff. */
    public void indexEntity(Entity entity) {
        try {
            Optional<Knowledge> kn = knowledge.findById(entity.knowledgeId());
            if (kn.isEmpty()) {
                fail(entity, "Owning knowledge " + entity.knowledgeId() + " not found", true);
                return;
            }
            SourceType sourceType = kn.get().connectorDetails().type();

            String text = extractText(entity);
            List<Chunk> chunks = chunking.chunk(entity, sourceType, text);
            List<Chunk> embedded = embed(chunks);

            // Idempotent replace: drop old chunks, write the fresh set keyed by chunkId.
            index.deleteByEntity(entity.id());
            index.indexChunks(embedded);

            entities.markIndexed(entity.id(), embedded.size(), embeddings.model(), Instant.now());
        } catch (RuntimeException e) {
            fail(entity, Errors.summary(e), false);
            LOG.log(Level.WARNING, "Indexing failed for entity " + entity.id(), e);
        }
    }

    /** Remove a tombstoned entity's chunks from the search index, then mark cleanup complete. */
    public void deleteEntityChunks(Entity entity) {
        try {
            index.deleteByEntity(entity.id());
            entities.markDeletionComplete(entity.id(), Instant.now());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to remove chunks for deleted entity " + entity.id(), e);
            // Leave needsReindex=true so the deletion is retried; the lease will lapse and re-claim.
        }
    }

    // ---- transform helpers -------------------------------------------------------------------

    private String extractText(Entity entity) {
        Entity.Content content = entity.content();
        if (content != null && !content.isFile() && content.text() != null) {
            return content.text();
        }
        if (content == null || !content.isFile()) {
            return "";
        }
        Path path = resolve(content.fileRef());
        String contentType = contentTypeOf(entity, path);
        try (InputStream in = Files.newInputStream(path)) {
            ParsedContent parsed = parsers.get(contentType).parse(in, contentType);
            return parsed.text();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file " + path + " for entity " + entity.id(), e);
        }
    }

    private List<Chunk> embed(List<Chunk> chunks) {
        List<Chunk> out = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += embedBatch) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + embedBatch));
            List<Embedding> vectors = embeddings.embedAll(batch.stream().map(Chunk::text).toList());
            for (int i = 0; i < batch.size(); i++) {
                out.add(batch.get(i).withEmbedding(vectors.get(i)));
            }
        }
        return out;
    }

    private void fail(Entity entity, String error, boolean terminal) {
        int retryCount = (entity.retry() == null ? 0 : entity.retry().count()) + 1;
        boolean dead = terminal || retryCount > retryLimit;
        EntityStatus resting = dead ? EntityStatus.FAILED : EntityStatus.INGESTED;
        Instant nextAttempt = dead ? null : Instant.now().plusSeconds(backoffSeconds);
        entities.markFailed(entity.id(), resting, error, retryCount, nextAttempt);
    }

    private static Path resolve(String fileRef) {
        if (fileRef.startsWith("file:")) {
            return Path.of(URI.create(fileRef));
        }
        return Path.of(fileRef);
    }

    private static String contentTypeOf(Entity entity, Path path) {
        Object ct = entity.raw() == null ? null : entity.raw().get("contentType");
        if (ct != null) {
            return ct.toString();
        }
        try {
            String probed = Files.probeContentType(path);
            return probed != null ? probed : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
