package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shape for a digest, both inbound (create) and outbound (read). Kept separate from the domain
 * record so the two can evolve independently, and so schedule is expressed as the flat
 * {@code cron}/{@code interval} pair the knowledge API already uses rather than a nested object.
 *
 * @param window   look-back window, e.g. {@code "1d"}; null for no time bound
 * @param interval how often it runs, e.g. {@code "1d"}; cron wins if both are set
 * @param taskId   a prompt-catalogue task to run over the results, or null for results only
 * @param onlyNew  drop results an earlier run already reported. Defaults true — that is what makes a
 *                 digest a digest rather than a repeated search
 */
public record DigestDto(
        String id,
        String name,
        String query,
        String sourceEntityId,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        String window,
        String cron,
        String interval,
        String taskId,
        Integer topK,
        Boolean collapseDuplicates,
        Integer maxChunksPerEntity,
        Boolean onlyNew,
        Boolean enabled,
        Instant nextRunAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * @throws IllegalArgumentException if it names neither a query nor a source document — a digest
     *                                  with nothing to search for would run forever and find nothing
     */
    public Digest toDomain() {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasDocument = sourceEntityId != null && !sourceEntityId.isBlank();
        if (!hasQuery && !hasDocument) {
            throw new IllegalArgumentException("a digest needs either query or sourceEntityId");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new Digest(
                id, name, query, sourceEntityId,
                knowledgeIds == null ? List.of() : knowledgeIds,
                filters == null ? Map.of() : filters,
                window,
                schedule(),
                taskId,
                topK == null ? Digest.DEFAULT_TOP_K : topK,
                collapseDuplicates != null && collapseDuplicates,
                maxChunksPerEntity,
                onlyNew == null || onlyNew,
                enabled == null || enabled,
                null, null, null);
    }

    private SyncSchedule schedule() {
        if (cron != null && !cron.isBlank()) {
            return SyncSchedule.ofCron(cron);
        }
        java.time.Duration parsed = io.personalassistant.common.Durations.parse(interval);
        return parsed == null ? SyncSchedule.NONE : SyncSchedule.ofInterval(parsed);
    }

    public static DigestDto from(Digest d) {
        return new DigestDto(d.id(), d.name(), d.query(), d.sourceEntityId(), d.knowledgeIds(),
                d.filters(), d.window(), d.schedule().cron(),
                d.schedule().interval() == null ? null : d.schedule().interval().toString(),
                d.taskId(), d.topK(), d.collapseDuplicates(), d.maxChunksPerEntity(), d.onlyNew(),
                d.enabled(), d.nextRunAt(), d.createdAt(), d.updatedAt());
    }
}
