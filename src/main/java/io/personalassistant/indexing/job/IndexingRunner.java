package io.personalassistant.indexing.job;

import io.personalassistant.common.Errors;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.indexing.chunking.ChunkingSpec;
import io.personalassistant.indexing.chunking.ChunkingSpecResolver;
import io.personalassistant.indexing.chunking.ChunkingStrategy;
import io.personalassistant.indexing.chunking.ChunkingStrategyRegistry;
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
    private final ChunkingStrategyRegistry chunking;
    private final ChunkingSpecResolver chunkingSpecs;
    private final EmbeddingProvider embeddings;
    private final SearchIndex index;

    @ConfigProperty(name = "app.indexing.embed-batch", defaultValue = "64")
    int embedBatch;

    @ConfigProperty(name = "app.indexing.retry-limit", defaultValue = "5")
    int retryLimit;

    @ConfigProperty(name = "app.indexing.backoff-seconds", defaultValue = "300")
    long backoffSeconds;

    @ConfigProperty(name = "app.indexing.lease-seconds", defaultValue = "900")
    long leaseSeconds;

    @Inject
    public IndexingRunner(EntityRepository entities, KnowledgeRepository knowledge,
                          ParserRegistry parsers, ChunkingStrategyRegistry chunking,
                          ChunkingSpecResolver chunkingSpecs,
                          EmbeddingProvider embeddings, SearchIndex index) {
        this.entities = entities;
        this.knowledge = knowledge;
        this.parsers = parsers;
        this.chunking = chunking;
        this.chunkingSpecs = chunkingSpecs;
        this.embeddings = embeddings;
        this.index = index;
    }

    /**
     * Index (or re-index) a single entity. Catches and records failures with retry/backoff.
     *
     * @param owner the worker that holds this entity's lease; every terminal write is fenced on it,
     *              so a run whose lease lapsed mid-flight records nothing (invariant 2)
     */
    public void indexEntity(Entity entity, String owner) {
        try {
            Optional<Knowledge> kn = knowledge.findById(entity.knowledgeId());
            if (kn.isEmpty()) {
                fail(entity, owner, "Owning knowledge " + entity.knowledgeId() + " not found", true);
                return;
            }
            SourceType sourceType = kn.get().connectorDetails().type();

            String text = extractText(entity);
            // Resolve the chunking strategy per knowledge on every pass: an entity indexed after a
            // chunking-settings change is chunked the new way, while already-indexed chunks are left
            // untouched (there is no re-chunk of existing entities — the "direct update" contract).
            ChunkingSpec spec = chunkingSpecs.resolve(kn.get());
            ChunkingStrategy strategy = chunking.get(spec.strategy());
            List<Chunk> chunks = strategy.chunk(entity, sourceType, text, spec);
            List<Chunk> embedded = embed(chunks);

            // Idempotent replace: drop old chunks, write the fresh set keyed by chunkId.
            index.deleteByEntity(entity.id());
            index.indexChunks(embedded);

            // The OpenSearch write precedes this fenced Mongo write by necessity, so a lease lost in
            // between means we wrote chunks the new owner will overwrite. That is benign — chunk ids
            // are entityId_ordinal and the replace is idempotent — so there is nothing to compensate;
            // just stop. Deleting what we wrote would actively corrupt the new owner's run.
            if (!entities.markIndexed(entity.id(), owner, embedded.size(), embeddings.model(), Instant.now())) {
                LOG.warning("Lost the indexing lease on entity " + entity.id()
                        + " before markIndexed; leaving it to the new owner");
            }
        } catch (RuntimeException e) {
            fail(entity, owner, Errors.summary(e), false);
            LOG.log(Level.WARNING, "Indexing failed for entity " + entity.id(), e);
        }
    }

    /** Remove a tombstoned entity's chunks from the search index, then mark cleanup complete. */
    public void deleteEntityChunks(Entity entity, String owner) {
        try {
            index.deleteByEntity(entity.id());
            if (!entities.markDeletionComplete(entity.id(), owner, Instant.now())) {
                LOG.warning("Lost the deletion lease on entity " + entity.id()
                        + " before markDeletionComplete; leaving it to the new owner");
            }
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
            // Contract check, not paranoia. A chunk that reaches the index without a vector is
            // written happily by OpenSearch (the mapping does not require the field), counted by
            // markIndexed, and then invisible to semantic search forever with no error anywhere.
            // Validating here rather than in one provider covers every provider by construction.
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding provider " + embeddings.model() + " returned "
                        + (vectors == null ? "null" : vectors.size() + " vectors")
                        + " for " + batch.size() + " chunks");
            }
            for (int i = 0; i < batch.size(); i++) {
                Embedding vector = vectors.get(i);
                if (vector == null || vector.vector() == null) {
                    throw new IllegalStateException("Embedding provider " + embeddings.model()
                            + " returned no vector for chunk " + batch.get(i).id());
                }
                out.add(batch.get(i).withEmbedding(vector));
            }
        }
        return out;
    }

    private void fail(Entity entity, String owner, String error, boolean terminal) {
        // Consecutive, not cumulative: markIndexed zeroes this on every success, so retryLimit means
        // "five failures in a row" rather than "five failures ever".
        int retryCount = (entity.retry() == null ? 0 : entity.retry().count()) + 1;
        boolean dead = terminal || retryCount > retryLimit;
        EntityStatus resting = dead ? EntityStatus.FAILED : EntityStatus.INGESTED;
        Instant nextAttempt = dead ? null : Instant.now().plusSeconds(backoffSeconds);
        if (!entities.markFailed(entity.id(), owner, resting, error, retryCount, nextAttempt)) {
            LOG.warning("Lost the indexing lease on entity " + entity.id()
                    + " before recording a failure; the new owner will record its own outcome");
        }
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
