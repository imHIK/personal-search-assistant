package io.personalassistant.indexing.job;

import io.personalassistant.common.ContentTypes;
import io.personalassistant.common.Errors;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.common.ratelimit.RateLimitedException;
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

    /**
     * Context fields prefixed to a chunk's text before embedding (see {@link Chunk#embedText}), resolved
     * per connector so a Gmail chunk can carry its sender while a Drive chunk carries its heading path.
     * Changing the resolved list invalidates existing vectors, so it means a re-index.
     */
    private final FieldSets fieldSets;

    @Inject
    public IndexingRunner(EntityRepository entities, KnowledgeRepository knowledge,
                          ParserRegistry parsers, ChunkingStrategyRegistry chunking,
                          ChunkingSpecResolver chunkingSpecs,
                          EmbeddingProvider embeddings, SearchIndex index, FieldSets fieldSets) {
        this.entities = entities;
        this.knowledge = knowledge;
        this.parsers = parsers;
        this.chunking = chunking;
        this.chunkingSpecs = chunkingSpecs;
        this.embeddings = embeddings;
        this.index = index;
        this.fieldSets = fieldSets;
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

            Extracted extracted = extract(entity);
            // Resolve the chunking strategy per knowledge on every pass: an entity indexed after a
            // chunking-settings change is chunked the new way, while already-indexed chunks are left
            // untouched (there is no re-chunk of existing entities — the "direct update" contract).
            ChunkingSpec spec = chunkingSpecs.resolve(kn.get());
            // Content type is offered as a tie-breaker so a spreadsheet can be chunked by row even in a
            // knowledge of mostly prose; an explicit per-knowledge strategy still wins.
            ChunkingStrategy strategy = chunking.get(spec.strategy(), extracted.contentType());
            List<Chunk> chunks = strategy.chunk(entity, sourceType, extracted.parsed(), spec);
            List<Chunk> embedded = embed(chunks, fieldSets.resolve(FieldSets.EMBED_CONTEXT, sourceType));

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
        } catch (RateLimitedException e) {
            defer(entity, owner, e);
        } catch (MissingContentException e) {
            missingContent(entity, owner, e);
        } catch (RuntimeException e) {
            fail(entity, owner, Errors.summary(e), false);
            LOG.log(Level.WARNING, "Indexing failed for entity " + entity.id(), e);
        }
    }

    /**
     * Dead-letter an entity whose staged content has been purged, and mark it for re-fetching.
     *
     * <p>Terminal on the first attempt on purpose: the bytes are not coming back on their own, so the
     * retry ladder would spend {@code retry-limit × backoff} arriving at the same place with the
     * error hidden behind a pending retry. The flag is what makes it recoverable — the next walk that
     * re-lists this item re-materializes it even though the source has not changed, so what used to
     * be a permanent dead letter is now one that heals on the next re-index of the knowledge.
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

    /**
     * A parsed entity plus the content type it was parsed as. The type travels with the content because
     * chunking-strategy selection can use it, and only this method knows it — inline text has none.
     */
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
            // Split out from the IOException below because the two need opposite handling: a file
            // that is absent will still be absent in five minutes, so retrying is pure delay, while
            // an unreadable-but-present one is exactly the transient case the ladder exists for.
            throw new MissingContentException(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file " + path + " for entity " + entity.id(), e);
        }
    }

    /**
     * The staged copy this entity's {@code fileRef} names is gone. Thrown and caught within this
     * class only — it is a routing signal for {@link #indexEntity}, not a failure mode callers can do
     * anything with.
     */
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
            // embedText, not text: the title and any structural locator are prefixed so a chunk of bare
            // rows is still linked to the document it came from. The stored/displayed text is unchanged.
            List<Embedding> vectors = embeddings.embedAll(
                    batch.stream().map(c -> c.embedText(contextFields)).toList());
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

    /**
     * A rate-limited entity is <em>deferred</em>, not failed: the wait was longer than a worker should
     * hold, so the reopening instant the limiter computed is written as {@code nextAttemptAt} and the
     * entity is picked up again once the window reopens. That instant beats the flat
     * {@code backoff-seconds} — it is when the limiter's rolling window actually reopens, or the
     * server's {@code Retry-After}, so the retry lands when the call can succeed.
     *
     * <p>Counted against {@code app.ratelimit.max-deferrals} rather than {@code retry-limit}, because a
     * deferral is the limiter working as designed. Charging it to the ordinary retry budget would
     * dead-letter a perfectly healthy entity after five throttled attempts. It is bounded at all only so
     * an unsatisfiable limit eventually surfaces as {@code FAILED} instead of retrying forever.
     */
    private void defer(Entity entity, String owner, RateLimitedException e) {
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
                dead ? null : e.retryAt())) {
            LOG.warning("Lost the indexing lease on entity " + entity.id()
                    + " before recording a rate-limit deferral; the new owner will retry");
        }
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

    /**
     * The connector's recorded type wins, since it may know something the bytes do not (a Drive export's
     * declared MIME, say). A generic {@code application/octet-stream} is treated as "the connector didn't
     * know" and re-detected — entities ingested before content-type detection was fixed carry exactly
     * that, and would otherwise keep being parsed by the fallback parser forever.
     */
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
