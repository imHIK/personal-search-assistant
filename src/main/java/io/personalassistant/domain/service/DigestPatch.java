package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import java.util.List;
import java.util.Map;

/**
 * Absent means not part of this edit, present sets the field, present-with-null clears it.
 * collapseDuplicates, onlyNew, enabled, useLlm and topK have no off state: a null there is their default.
 */
public record DigestPatch(
        Patched<String> name,
        Patched<String> query,
        Patched<List<String>> knowledgeIds,
        Patched<Map<String, Object>> filters,
        Patched<String> window,
        Patched<SyncSchedule> schedule,
        Patched<String> taskId,
        Patched<Boolean> useLlm,
        Patched<Integer> topK,
        Patched<Boolean> collapseDuplicates,
        Patched<Integer> maxChunksPerEntity,
        Patched<Boolean> onlyNew,
        Patched<Boolean> enabled,
        Patched<List<String>> channelIds) {

    public DigestPatch {
        name = Patched.orAbsent(name);
        query = Patched.orAbsent(query);
        knowledgeIds = Patched.orAbsent(knowledgeIds);
        filters = Patched.orAbsent(filters);
        window = Patched.orAbsent(window);
        schedule = Patched.orAbsent(schedule);
        taskId = Patched.orAbsent(taskId);
        useLlm = Patched.orAbsent(useLlm);
        topK = Patched.orAbsent(topK);
        collapseDuplicates = Patched.orAbsent(collapseDuplicates);
        maxChunksPerEntity = Patched.orAbsent(maxChunksPerEntity);
        onlyNew = Patched.orAbsent(onlyNew);
        enabled = Patched.orAbsent(enabled);
        channelIds = Patched.orAbsent(channelIds);
    }

    public DigestPatch(Patched<String> name, Patched<String> query, Patched<List<String>> knowledgeIds,
                       Patched<Map<String, Object>> filters, Patched<String> window,
                       Patched<SyncSchedule> schedule, Patched<String> taskId, Patched<Integer> topK,
                       Patched<Boolean> collapseDuplicates, Patched<Integer> maxChunksPerEntity,
                       Patched<Boolean> onlyNew, Patched<Boolean> enabled) {
        this(name, query, knowledgeIds, filters, window, schedule, taskId, null, topK,
                collapseDuplicates, maxChunksPerEntity, onlyNew, enabled, null);
    }

    /**
     * Leaves nextRunAt alone, since an edit is no reason to run early and moving it would let repeated saves
     * starve the schedule, and historyResetAt, which only resetHistory sets.
     */
    public Digest applyTo(Digest existing) {
        return new Digest(
                existing.id(),
                name.orElse(existing.name()),
                query.orElse(existing.query()),
                knowledgeIds.orElse(existing.knowledgeIds()),
                filters.orElse(existing.filters()),
                window.orElse(existing.window()),
                schedule.orElse(existing.schedule()),
                taskId.orElse(existing.taskId()),
                flag(useLlm.orElse(existing.useLlm()), true),
                topKOr(existing.topK()),
                flag(collapseDuplicates.orElse(existing.collapseDuplicates()), false),
                maxChunksPerEntity.orElse(existing.maxChunksPerEntity()),
                onlyNewOr(existing.onlyNew()),
                enabledOr(existing.enabled()),
                existing.nextRunAt(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.historyResetAt(),
                channelIds.orElse(existing.channelIds()));
    }

    private int topKOr(int current) {
        Integer patched = topK.orElse(current);
        return patched == null ? Digest.DEFAULT_TOP_K : patched;
    }

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
