package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param window null means no time bound
 * @param interval cron wins when both are set
 * @param useLlm false skips the task without forgetting it; defaults true
 * @param onlyNew defaults true
 */
public record DigestDto(
        String id,
        String name,
        String query,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        String window,
        String cron,
        String interval,
        String taskId,
        Boolean useLlm,
        Integer topK,
        Boolean collapseDuplicates,
        Integer maxChunksPerEntity,
        Boolean onlyNew,
        Boolean enabled,
        Instant nextRunAt,
        Instant createdAt,
        Instant updatedAt,
        Instant historyResetAt,
        List<String> channelIds) {

    /** @throws IllegalArgumentException on a blank query or name */
    public Digest toDomain() {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new Digest(
                id, name, query,
                knowledgeIds == null ? List.of() : knowledgeIds,
                filters == null ? Map.of() : filters,
                DigestPatchDto.checkedWindow(window),
                schedule(),
                taskId,
                useLlm == null || useLlm,
                topK == null ? Digest.DEFAULT_TOP_K : topK,
                collapseDuplicates != null && collapseDuplicates,
                maxChunksPerEntity,
                onlyNew == null || onlyNew,
                enabled == null || enabled,
                null, null, null, null,
                channelIds == null ? List.of() : channelIds);
    }

    /** @throws IllegalArgumentException if an interval is given but unparseable */
    private SyncSchedule schedule() {
        if (cron != null && !cron.isBlank()) {
            return SyncSchedule.ofCron(cron);
        }
        if (interval == null || interval.isBlank()) {
            return SyncSchedule.NONE;
        }
        java.time.Duration parsed = io.personalassistant.common.Durations.parse(interval);
        if (parsed == null) {
            throw new IllegalArgumentException("interval \"" + interval + "\" is not a duration");
        }
        return SyncSchedule.ofInterval(parsed);
    }

    public static DigestDto from(Digest d) {
        return new DigestDto(d.id(), d.name(), d.query(), d.knowledgeIds(),
                d.filters(), d.window(), d.schedule().cron(),
                d.schedule().interval() == null ? null : d.schedule().interval().toString(),
                d.taskId(), d.useLlm(), d.topK(), d.collapseDuplicates(), d.maxChunksPerEntity(),
                d.onlyNew(), d.enabled(), d.nextRunAt(), d.createdAt(), d.updatedAt(), d.historyResetAt(),
                d.channelIds());
    }
}
