package io.personalassistant.indexing.enrichment;

import io.personalassistant.agent.MetadataEnricher;
import io.personalassistant.common.Errors;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.EnrichmentOutcome;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.Task;
import io.personalassistant.storage.repository.TaskRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Decides, per indexing pass, whether an entity's enriched fields are current, and runs the knowledge's
 * metadata task when they are not. An LLM failure never fails the pass: the entity is indexed without fresh
 * values and the error is recorded for the next re-index to retry.
 */
@ApplicationScoped
public class EntityEnrichment {

    private static final Logger LOG = Logger.getLogger(EntityEnrichment.class.getName());

    /** @param values what the chunks carry: fresh, kept, or empty */
    public record Result(EnrichmentOutcome outcome, Map<String, Object> values) {}

    private final TaskRepository tasks;
    private final MetadataEnricher enricher;
    private final SearchIndex index;

    /** taskId@version already mapped by this process; a recreated index is re-mapped after a restart. */
    private final Set<String> mapped = ConcurrentHashMap.newKeySet();

    @Inject
    public EntityEnrichment(TaskRepository tasks, MetadataEnricher enricher, SearchIndex index) {
        this.tasks = tasks;
        this.enricher = enricher;
        this.index = index;
    }

    /** @throws RateLimitedException so the runner defers the whole pass rather than recording an error */
    public Result resolve(Knowledge knowledge, Entity entity, String text) {
        String taskId = knowledge.config().enrichment().taskId();
        if (taskId == null) {
            boolean stored = entity.enrichment() != null || !entity.enriched().isEmpty();
            return new Result(stored ? EnrichmentOutcome.clear() : EnrichmentOutcome.keep(), Map.of());
        }

        Optional<Task> found = tasks.findById(taskId).filter(Task::metadata);
        if (found.isEmpty()) {
            return failed(entity, taskId, null, "Task " + taskId + " is gone or no longer a metadata task");
        }
        Task task = found.get();
        if (entity.enrichment() != null
                && entity.enrichment().isCurrentFor(task.id(), task.updatedAt(), entity.checksum())) {
            return new Result(EnrichmentOutcome.keep(), entity.enriched());
        }

        try {
            Map<String, Object> values = enricher.enrich(task, entity.title(), text);
            ensureMapped(task);
            return new Result(EnrichmentOutcome.set(values, stamp(task.id(), task.updatedAt(), entity, null)),
                    values);
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            return failed(entity, task.id(), task.updatedAt(), Errors.summary(e));
        }
    }

    private Result failed(Entity entity, String taskId, Instant taskVersion, String error) {
        LOG.warning("Enrichment of entity " + entity.id() + " with " + taskId + " failed; indexing it "
                + "without fresh values: " + error);
        return new Result(EnrichmentOutcome.error(stamp(taskId, taskVersion, entity, error)), entity.enriched());
    }

    private void ensureMapped(Task task) {
        String key = task.id() + "@" + task.updatedAt();
        if (mapped.contains(key)) {
            return;
        }
        try {
            index.ensureMetadataFields(task.fieldTypes());
            mapped.add(key);
        } catch (RuntimeException e) {
            // Saving the task or knowledge already refused a conflicting type, so this is transient; the
            // values still index, dynamically typed.
            LOG.warning("Could not map the fields of " + task.id() + ": " + Errors.summary(e));
        }
    }

    private static Entity.Enrichment stamp(String taskId, Instant taskVersion, Entity entity, String error) {
        return new Entity.Enrichment(taskId, taskVersion, entity.checksum(), Instant.now(), error);
    }
}
