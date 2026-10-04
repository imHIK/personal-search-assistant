package io.personalassistant.indexing.job;

import io.personalassistant.common.ContentTypes;
import io.personalassistant.common.Errors;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.domain.model.EnrichmentOutcome;
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
import io.personalassistant.indexing.enrichment.EntityEnrichment;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
    private final EntityEnrichment enrichment;

    @ConfigProperty(name = "app.indexing.embed-batch", defaultValue = "15")
    int embedBatch;

    @ConfigProperty(name = "app.indexing.retry-limit", defaultValue = "5")
    int retryLimit;

    @ConfigProperty(name = "app.indexing.backoff-seconds", defaultValue = "300")
    long backoffSeconds;

    @ConfigProperty(name = "app.indexing.lease-seconds", defaultValue = "900")
    long leaseSeconds;

    @ConfigProperty(name = "app.ratelimit.max-deferrals", defaultValue = "20")
    int maxDeferrals;

    /** Changing the resolved list invalidates existing vectors. */
    private final FieldSets fieldSets;

    @Inject
    public IndexingRunner(EntityRepository entities, KnowledgeRepository knowledge,
                          ParserRegistry parsers, ChunkingStrategyRegistry chunking,
                          ChunkingSpecResolver chunkingSpecs,
                          EmbeddingProvider embeddings, SearchIndex index, FieldSets fieldSets,
                          EntityEnrichment enrichment) {
        this.entities = entities;
        this.knowledge = knowledge;
        this.parsers = parsers;
        this.chunking = chunking;
        this.chunkingSpecs = chunkingSpecs;
        this.embeddings = embeddings;
        this.index = index;
        this.fieldSets = fieldSets;
        this.enrichment = enrichment;
    }

    /** @param owner every terminal write is fenced on it, so a run whose lease lapsed records nothing */
    public void indexEntity(Entity entity, String owner) {
        // Hoisted so a pass that fails after enriching still records it, sparing the retry an LLM call.
        EnrichmentOutcome enrichedOutcome = EnrichmentOutcome.keep();
        try {
            Optional<Knowledge> kn = knowledge.findById(entity.knowledgeId());
            if (kn.isEmpty()) {
                fail(entity, owner, "Owning knowledge " + entity.knowledgeId() + " not found", true);
                return;
            }
            SourceType sourceType = kn.get().connectorDetails().type();

            Extracted extracted = extract(entity);
            // Before chunking, so the enriched fields ride on every chunk and search filters reach them.
            EntityEnrichment.Result enriched = enrichment.resolve(kn.get(), entity, extracted.parsed().text());
            enrichedOutcome = enriched.outcome();
            Entity toChunk = enriched.values().isEmpty() ? entity
                    : entity.withMetadata(Entity.mergeEnriched(entity.metadata(), enriched.values()));
            // Resolved on every pass: a chunking change applies to the next entity indexed; nothing is
            // re-chunked.
            ChunkingSpec spec = chunkingSpecs.resolve(kn.get());
            ChunkingStrategy strategy = chunking.get(spec.strategy(), extracted.contentType());
            List<Chunk> chunks = strategy.chunk(toChunk, sourceType, extracted.parsed(), spec);
            List<Chunk> embedded = embed(chunks, fieldSets.resolve(FieldSets.EMBED_CONTEXT, sourceType));

            // Idempotent replace: drop the old chunks, then write the fresh set keyed by chunkId.
            index.deleteByEntity(entity.id());
            index.indexChunks(embedded);

            // The OpenSearch write precedes this fenced one, so a lost lease leaves chunks the new owner will
            // overwrite; the ids are entityId_ordinal, so that is benign. Just stop: deleting them would
            // corrupt the new owner's run.
            if (!entities.markIndexed(entity.id(), owner, embedded.size(), embeddings.model(), Instant.now(),
                    enriched.outcome())) {
                LOG.warning("Lost the indexing lease on entity " + entity.id()
                        + " before markIndexed; leaving it to the new owner");
            }
        } catch (RateLimitedException e) {
            defer(entity, owner, e, enrichedOutcome);
        } catch (MissingContentException e) {
            missingContent(entity, owner, e);
        } catch (RuntimeException e) {
            fail(entity, owner, Errors.summary(e), false, enrichedOutcome);
            LOG.log(Level.WARNING, "Indexing failed for entity " + entity.id(), e);
        }
    }

    /**
     * Terminal on the first attempt: the bytes are not coming back, so retrying would only hide the error.
     * The refetch flag lets the next walk re-materialize the item.
     */
    private void missingContent(Entity entity, String owner, MissingContentException e) {
        String error = e.getMessage() + "; the source copy must be re-fetched";
        LOG.warning("Entity " + entity.id() + " lost its staged content (" + e.path
                + "); dead-lettered for re-fetch");
        if (!entities.markContentMissing(entity.id(), owner, error)) {
            LOG.warning("Lost the indexing lease on entity " + entity.id()
                    + " before markContentMissing; leaving it to the new owner");
        }
    }

    public void deleteEntityChunks(Entity entity, String owner) {
        try {
            index.deleteByEntity(entity.id());
            if (!entities.markDeletionComplete(entity.id(), owner, Instant.now())) {
                LOG.warning("Lost the deletion lease on entity " + entity.id()
                        + " before markDeletionComplete; leaving it to the new owner");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to remove chunks for deleted entity " + entity.id(), e);
            // needsReindex stays set, so the deletion is retried once the lease lapses.
        }
    }

    private record Extracted(ParsedContent parsed, String contentType) {}

    private Extracted extract(Entity entity) {
        Entity.Content content = entity.content();
        if (content != null && !content.isFile() && content.text() != null) {
            return new Extracted(new ParsedContent(content.text(), Map.of()), null);
        }
        if (content == null || !content.isFile()) {
            return new Extracted(new ParsedContent("", Map.of()), null);
        }
        Path path = resolve(content.fileRef());
        String contentType = contentTypeOf(entity, path);
        try (InputStream in = Files.newInputStream(path)) {
            return new Extracted(parsers.get(contentType).parse(in, contentType), contentType);
        } catch (NoSuchFileException e) {
            // Not an IOException: a missing file stays missing, while an unreadable one is the transient case
            // retries exist for.
            throw new MissingContentException(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file " + path + " for entity " + entity.id(), e);
        }
    }

    private static final class MissingContentException extends RuntimeException {
        private final transient Path path;

        MissingContentException(Path path) {
            super("Staged content missing at " + path);
            this.path = path;
        }
    }

    private List<Chunk> embed(List<Chunk> chunks, List<String> contextFields) {
        List<Chunk> out = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += embedBatch) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + embedBatch));
            // embedText, not text: the title and locators are prefixed for the vector; the stored text is
            // unchanged.
            List<Embedding> vectors = embeddings.embedAll(
                    batch.stream().map(c -> c.embedText(contextFields)).toList());
            // A chunk indexed without a vector is invisible to semantic search forever, with no error
            // anywhere; checking here covers every provider.
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

    /**
     * Deferred, not failed: nextAttemptAt is when the limiter's window reopens. Counted against
     * {@code app.ratelimit.max-deferrals}, not the retry limit, so a healthy throttled entity is never
     * dead-lettered.
     */
    private void defer(Entity entity, String owner, RateLimitedException e, EnrichmentOutcome enrichedOutcome) {
        int count = (entity.retry() == null ? 0 : entity.retry().count()) + 1;
        boolean dead = count > maxDeferrals;
        EntityStatus resting = dead ? EntityStatus.FAILED : EntityStatus.INGESTED;
        String reason = "Rate limited on " + e.key() + "; deferred to " + e.retryAt()
                + " (" + count + " consecutive deferrals)";
        if (dead) {
            LOG.warning("Entity " + entity.id() + " has been rate limited " + count
                    + " times in a row on " + e.key() + "; parking it FAILED. Raise the limit on that"
                    + " account, or app.ratelimit.max-deferrals, then retry it.");
        } else {
            LOG.fine(() -> reason + " for entity " + entity.id());
        }
        if (!entities.markFailed(entity.id(), owner, resting, reason, count,
                dead ? null : e.retryAt(), enrichedOutcome)) {
            LOG.warning("Lost the indexing lease on entity " + entity.id()
                    + " before recording a rate-limit deferral; the new owner will retry");
        }
    }

    private void fail(Entity entity, String owner, String error, boolean terminal) {
        fail(entity, owner, error, terminal, EnrichmentOutcome.keep());
    }

    private void fail(Entity entity, String owner, String error, boolean terminal,
                      EnrichmentOutcome enrichedOutcome) {
        // Consecutive, not cumulative: markIndexed zeroes it on every success.
        int retryCount = (entity.retry() == null ? 0 : entity.retry().count()) + 1;
        boolean dead = terminal || retryCount > retryLimit;
        EntityStatus resting = dead ? EntityStatus.FAILED : EntityStatus.INGESTED;
        Instant nextAttempt = dead ? null : Instant.now().plusSeconds(backoffSeconds);
        if (!entities.markFailed(entity.id(), owner, resting, error, retryCount, nextAttempt, enrichedOutcome)) {
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

    /** The connector's recorded type wins, but octet-stream counts as unknown and is re-detected. */
    private static String contentTypeOf(Entity entity, Path path) {
        Object ct = entity.raw() == null ? null : entity.raw().get("contentType");
        if (ct != null && !ContentTypes.UNKNOWN.equals(ct.toString()) && !ct.toString().isBlank()) {
            return ct.toString();
        }
        return ContentTypes.detect(path);
    }

    Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
