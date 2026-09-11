package io.personalassistant.domain.model;

import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.search.SearchQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A saved search that runs on a schedule and keeps its results: "tell me what is new, on this subject,
 * every morning".
 *
 * <p>Deliberately generic. The job-hunt case that motivated it — new postings scored against a CV — is
 * one row of this table, not a feature: {@link #sourceEntityId} points the search at the CV,
 * {@link #taskId} names a scoring prompt, and {@link #window} bounds it to the last day. The same shape
 * gives "everything new in Drive about project X, weekly" with no code involved.
 *
 * <p>Persisted in the Mongo {@code digests} collection; each execution appends a {@link DigestRun}.
 *
 * @param id           stable id, {@code dig_...}
 * @param name         human label, shown in the console
 * @param query        the search text. With {@link #sourceEntityId} set this becomes a statement of
 *                     intent that steers how the document is decomposed, and may be blank
 * @param sourceEntityId an entity to search <em>by</em> rather than searching for, or null
 * @param knowledgeIds restrict to these sources; empty searches everything
 * @param filters      index filters, exactly as {@code POST /api/search} takes them
 * @param window       how far back a run looks. The run adds this as a range filter on the chunk's
 *                     {@code indexedAt}; null means "no time bound", which for a digest is unusual but
 *                     legitimate for a standing "top N on this subject" view
 * @param schedule     when it runs, as a cron or interval — resolved by the same
 *                     {@code ScheduleResolver} the ingestion schedulers use
 * @param taskId       a prompt-catalogue task to run over the results, or null for results only
 * @param topK         results to keep
 * @param collapseDuplicates group near-identical results
 * @param maxChunksPerEntity cap chunks per document; 1 gives one result per document
 * @param onlyNew      whether to drop results that appeared in an earlier run. This is what makes a
 *                     digest a digest rather than a repeated search
 * @param enabled      master switch; a disabled digest is never scheduled but can still be run by hand
 * @param nextRunAt    when the scheduler may next run it; null means "due now"
 * @param historyResetAt earlier runs are ignored when working out what has already been reported;
 *                     null means the whole history counts.
 *                     <p>This exists so "show me these again" and "forget this digest ever ran" can be
 *                     different operations. The run history <em>is</em> the already-seen set, so
 *                     deleting runs to replay a backlog would also destroy the record of what was sent
 *                     — and the record is half of why runs are kept. Bounding the read instead leaves
 *                     the history intact and visible while the next run starts from nothing
 */
public record Digest(
        String id,
        String name,
        String query,
        String sourceEntityId,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        String window,
        SyncSchedule schedule,
        String taskId,
        int topK,
        boolean collapseDuplicates,
        Integer maxChunksPerEntity,
        boolean onlyNew,
        boolean enabled,
        Instant nextRunAt,
        Instant createdAt,
        Instant updatedAt,
        Instant historyResetAt) {

    /** Default result count, matching the search API's own default. */
    public static final int DEFAULT_TOP_K = 10;

    /** A digest whose history has never been reset — the shape every caller but the reset used. */
    public Digest(String id, String name, String query, String sourceEntityId,
                  List<String> knowledgeIds, Map<String, Object> filters, String window,
                  SyncSchedule schedule, String taskId, int topK, boolean collapseDuplicates,
                  Integer maxChunksPerEntity, boolean onlyNew, boolean enabled, Instant nextRunAt,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, query, sourceEntityId, knowledgeIds, filters, window, schedule, taskId, topK,
                collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt, createdAt,
                updatedAt, null);
    }

    public Digest {
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        if (query != null && query.isBlank()) {
            query = "";
        }
        if (sourceEntityId != null && sourceEntityId.isBlank()) {
            sourceEntityId = null;
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
    }

    /** The look-back window as a duration, or null when the digest has no time bound. */
    public Duration windowDuration() {
        return Durations.parse(window);
    }

    /** The search this digest runs, before its time window is applied. */
    public SearchQuery toQuery() {
        return new SearchQuery(query == null ? "" : query, knowledgeIds, filters, topK,
                SearchQuery.Mode.HYBRID, false, maxChunksPerEntity, collapseDuplicates, sourceEntityId);
    }

    public Digest withNextRunAt(Instant next) {
        return new Digest(id, name, query, sourceEntityId, knowledgeIds, filters, window, schedule,
                taskId, topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, next,
                createdAt, updatedAt, historyResetAt);
    }

    public Digest withEnabled(boolean nowEnabled, Instant updatedAt) {
        return new Digest(id, name, query, sourceEntityId, knowledgeIds, filters, window, schedule,
                taskId, topK, collapseDuplicates, maxChunksPerEntity, onlyNew, nowEnabled, nextRunAt,
                createdAt, updatedAt, historyResetAt);
    }

    /** The same digest, marked as edited at {@code at}. */
    public Digest withTouched(Instant at) {
        return new Digest(id, name, query, sourceEntityId, knowledgeIds, filters, window, schedule,
                taskId, topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt,
                createdAt, at, historyResetAt);
    }

    /** Start the already-seen set again from {@code at}, keeping every recorded run. */
    public Digest withHistoryResetAt(Instant at) {
        return new Digest(id, name, query, sourceEntityId, knowledgeIds, filters, window, schedule,
                taskId, topK, collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, nextRunAt,
                createdAt, at, at);
    }
}
