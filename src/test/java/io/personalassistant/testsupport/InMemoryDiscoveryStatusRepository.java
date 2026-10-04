package io.personalassistant.testsupport;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.DiscoveryOutcome;
import io.personalassistant.storage.repository.DiscoveryStatusRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors the Mongo fold: counters accumulate, and a {@code FAILED} run keeps the last good
 * {@code iterablesFound} and {@code lastCounts}.
 */
public class InMemoryDiscoveryStatusRepository implements DiscoveryStatusRepository {

    public final Map<String, DiscoveryStatus> store = new LinkedHashMap<>();

    @Override
    public void record(DiscoveryStatus.Run run) {
        String id = Ids.discoveryFor(run.knowledgeId(), run.direction().name());
        DiscoveryStatus prev = store.get(id);
        Instant now = run.ranAt();
        boolean ok = run.outcome() == DiscoveryOutcome.OK;

        long runCount = (prev == null ? 0 : prev.runCount()) + 1;
        long failureCount = (prev == null ? 0 : prev.failureCount()) + (ok ? 0 : 1);
        int iterablesFound = ok ? run.iterablesFound() : (prev == null ? 0 : prev.iterablesFound());
        DiscoveryStatus.Counts counts = ok ? run.counts()
                : (prev == null ? DiscoveryStatus.Counts.zero() : prev.lastCounts());
        Instant createdAt = prev == null ? now : prev.createdAt();

        store.put(id, new DiscoveryStatus(id, run.knowledgeId(), run.direction(),
                run.outcome(), run.trigger(), now, iterablesFound, counts,
                runCount, failureCount, run.error(), createdAt, now));
    }

    @Override
    public Optional<DiscoveryStatus> find(String knowledgeId, CursorDirection direction) {
        return Optional.ofNullable(store.get(Ids.discoveryFor(knowledgeId, direction.name())));
    }

    @Override
    public List<DiscoveryStatus> findByKnowledge(String knowledgeId) {
        return store.values().stream().filter(s -> s.knowledgeId().equals(knowledgeId)).toList();
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        store.values().removeIf(s -> s.knowledgeId().equals(knowledgeId));
    }
}
