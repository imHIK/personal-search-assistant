package io.personalassistant.domain.model;

import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.search.SearchQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A saved search run on a schedule, keeping its results.
 *
 * @param window how far back a run looks, as a range filter on the chunk's {@code indexedAt}; null means no
 *               time bound
 * @param useLlm false skips the task without forgetting which one it is
 * @param onlyNew drop results an earlier run reported: what makes a digest a digest
 * @param enabled a disabled digest is never scheduled but can still be run by hand
 * @param nextRunAt null means due now
 * @param historyResetAt runs before it do not count as already reported, so the seen-set clears without
 *                       deleting the history; null means all of it counts
 */
public record Digest(
        String id,
        String name,
        String query,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        String window,
        SyncSchedule schedule,
        String taskId,
        boolean useLlm,
        int topK,
        boolean collapseDuplicates,
        Integer maxChunksPerEntity,
        boolean onlyNew,
        boolean enabled,
        Instant nextRunAt,
        Instant createdAt,
        Instant updatedAt,
        Instant historyResetAt,
        List<String> channelIds) {

    public static final int DEFAULT_TOP_K = 10;

    public Digest(String id, String name, String query, List<String> knowledgeIds,
                  Map<String, Object> filters, String window, SyncSchedule schedule, String taskId,
                  int topK, boolean collapseDuplicates, Integer maxChunksPerEntity, boolean onlyNew,
                  boolean enabled, Instant nextRunAt, Instant createdAt, Instant updatedAt) {
        this(id, name, query, knowledgeIds, filters, window, schedule, taskId, true, topK,
                collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt, createdAt,
                updatedAt, null, List.of());
    }

    public Digest(String id, String name, String query, List<String> knowledgeIds,
                  Map<String, Object> filters, String window, SyncSchedule schedule, String taskId,
                  int topK, boolean collapseDuplicates, Integer maxChunksPerEntity, boolean onlyNew,
                  boolean enabled, Instant nextRunAt, Instant createdAt, Instant updatedAt,
                  Instant historyResetAt) {
        this(id, name, query, knowledgeIds, filters, window, schedule, taskId, true, topK,
                collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt, createdAt,
                updatedAt, historyResetAt, List.of());
    }

    public Digest {
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        if (query != null && query.isBlank()) {
            query = "";
        }
        if (taskId != null && taskId.isBlank()) {
            taskId = null;
        }
        if (window != null && window.isBlank()) {
            window = null;
        }
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        schedule = schedule == null ? SyncSchedule.NONE : schedule;
        // Distinct, so ticking a channel twice cannot queue two copies of every run.
        channelIds = channelIds == null ? List.of()
                : channelIds.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
    }

    public Duration windowDuration() {
        return Durations.parse(window);
    }

    /** Before the look-back window is applied. */
    public SearchQuery toQuery() {
        return new SearchQuery(query == null ? "" : query, knowledgeIds, filters, topK,
                SearchQuery.Mode.HYBRID, false, maxChunksPerEntity, collapseDuplicates);
    }

    public Digest withNextRunAt(Instant next) {
        return new Digest(id, name, query, knowledgeIds, filters, window, schedule, taskId, useLlm,
                topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, next, createdAt,
                updatedAt, historyResetAt, channelIds);
    }

    public Digest withEnabled(boolean nowEnabled, Instant updatedAt) {
        return new Digest(id, name, query, knowledgeIds, filters, window, schedule, taskId, useLlm,
                topK, collapseDuplicates, maxChunksPerEntity, onlyNew, nowEnabled, nextRunAt, createdAt,
                updatedAt, historyResetAt, channelIds);
    }

    public Digest withTouched(Instant at) {
        return new Digest(id, name, query, knowledgeIds, filters, window, schedule, taskId, useLlm,
                topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt, createdAt,
                at, historyResetAt, channelIds);
    }

    public Digest withHistoryResetAt(Instant at) {
        return new Digest(id, name, query, knowledgeIds, filters, window, schedule, taskId, useLlm,
                topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt, createdAt,
                at, at, channelIds);
    }
}
