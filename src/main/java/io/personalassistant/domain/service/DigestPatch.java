package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import java.util.List;
import java.util.Map;

/**
 * A partial edit to an existing {@link Digest}. Every value is a {@link Patched}: absent means "not
 * part of this edit", present means "set to this" — and present-with-null means "clear it".
 *
 * <p>That third state is why this does not use {@link java.util.Optional} the way
 * {@link KnowledgePatch} does. Half the console's controls for these fields exist to turn something
 * <em>off</em>, and every one of them sends a JSON null.
 *
 * <p><b>Why this exists at all.</b> Editing used to be delete-and-recreate, which quietly destroyed the
 * digest's runs — and the runs are the already-seen set, so renaming a digest cost it its memory and the
 * next run re-reported everything in the window. Nothing in the API said so. A patch that changes one
 * field and leaves the history alone is the fix; {@link DigestService#resetHistory} is there for when
 * clearing it is what you actually meant.
 *
 * <p>{@code collapseDuplicates}, {@code onlyNew}, {@code enabled} and {@code topK} have no "off" to
 * clear to, so a null there means the field's default rather than an unset primitive.
 */
public record DigestPatch(
        Patched<String> name,
        Patched<String> query,
        Patched<String> sourceEntityId,
        Patched<List<String>> knowledgeIds,
        Patched<Map<String, Object>> filters,
        Patched<String> window,
        Patched<SyncSchedule> schedule,
        Patched<String> taskId,
        Patched<Integer> topK,
        Patched<Boolean> collapseDuplicates,
        Patched<Integer> maxChunksPerEntity,
        Patched<Boolean> onlyNew,
        Patched<Boolean> enabled,
        Patched<List<String>> channelIds) {

    /** Normalize any {@code null} to its absent form so callers can pass either. */
    public DigestPatch {
        name = Patched.orAbsent(name);
        query = Patched.orAbsent(query);
        sourceEntityId = Patched.orAbsent(sourceEntityId);
        knowledgeIds = Patched.orAbsent(knowledgeIds);
        filters = Patched.orAbsent(filters);
        window = Patched.orAbsent(window);
        schedule = Patched.orAbsent(schedule);
        taskId = Patched.orAbsent(taskId);
        topK = Patched.orAbsent(topK);
        collapseDuplicates = Patched.orAbsent(collapseDuplicates);
        maxChunksPerEntity = Patched.orAbsent(maxChunksPerEntity);
        onlyNew = Patched.orAbsent(onlyNew);
        enabled = Patched.orAbsent(enabled);
        channelIds = Patched.orAbsent(channelIds);
    }

    /** An edit that leaves the digest's channels alone. */
    public DigestPatch(Patched<String> name, Patched<String> query, Patched<String> sourceEntityId,
                       Patched<List<String>> knowledgeIds, Patched<Map<String, Object>> filters,
                       Patched<String> window, Patched<SyncSchedule> schedule, Patched<String> taskId,
                       Patched<Integer> topK, Patched<Boolean> collapseDuplicates,
                       Patched<Integer> maxChunksPerEntity, Patched<Boolean> onlyNew, Patched<Boolean> enabled) {
        this(name, query, sourceEntityId, knowledgeIds, filters, window, schedule, taskId, topK,
                collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, null);
    }

    /**
     * This edit applied to {@code existing}. Deliberately does not touch {@code nextRunAt} — an edit is
     * not a reason to re-run early, and moving it would let repeated saves starve the schedule — nor
     * {@code historyResetAt}, which only {@link DigestService#resetHistory} sets.
     *
     * <p>A cleared field arrives here as a present null and is written as one. {@link Digest}'s own
     * constructor is what turns that into the canonical empty form — a blank window or task id becomes
     * null, a null topK becomes the default — so "cleared" means the same thing however it arrived.
     */
    public Digest applyTo(Digest existing) {
        return new Digest(
                existing.id(),
                name.orElse(existing.name()),
                query.orElse(existing.query()),
                sourceEntityId.orElse(existing.sourceEntityId()),
                knowledgeIds.orElse(existing.knowledgeIds()),
                filters.orElse(existing.filters()),
                window.orElse(existing.window()),
                schedule.orElse(existing.schedule()),
                taskId.orElse(existing.taskId()),
                // A cleared topK is the default, not zero: the field has no "off".
                topKOr(existing.topK()),
                flag(collapseDuplicates.orElse(existing.collapseDuplicates()), false),
                maxChunksPerEntity.orElse(existing.maxChunksPerEntity()),
                onlyNewOr(existing.onlyNew()),
                enabledOr(existing.enabled()),
                existing.nextRunAt(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.historyResetAt(),
                // A cleared list sends nowhere; the Digest constructor makes an empty list of the null.
                channelIds.orElse(existing.channelIds()));
    }

    private int topKOr(int current) {
        Integer patched = topK.orElse(current);
        return patched == null ? Digest.DEFAULT_TOP_K : patched;
    }

    /**
     * A cleared boolean is its default rather than a {@link NullPointerException}. {@code onlyNew}
     * defaults on — it is what makes a digest a digest — and {@code enabled} likewise: clearing the
     * switch is not a way to pause something.
     */
    private boolean onlyNewOr(boolean current) {
        return flag(onlyNew.orElse(current), true);
    }

    private boolean enabledOr(boolean current) {
        return flag(enabled.orElse(current), true);
    }

    private static boolean flag(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
