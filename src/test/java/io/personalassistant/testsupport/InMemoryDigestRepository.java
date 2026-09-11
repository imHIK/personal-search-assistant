package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.storage.repository.DigestRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory {@link DigestRepository} for tests, mirroring the Mongo adapter's ordering. */
public class InMemoryDigestRepository implements DigestRepository {

    public final Map<String, Digest> store = new LinkedHashMap<>();
    public final List<DigestRun> runs = new ArrayList<>();

    @Override
    public Digest save(Digest digest) {
        store.put(digest.id(), digest);
        return digest;
    }

    @Override
    public Optional<Digest> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Digest> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<Digest> findDue(Instant now, int limit) {
        return store.values().stream()
                .filter(Digest::enabled)
                .filter(d -> d.nextRunAt() == null || !d.nextRunAt().isAfter(now))
                .limit(limit)
                .toList();
    }

    @Override
    public void delete(String id) {
        store.remove(id);
        deleteRuns(id);
    }

    @Override
    public DigestRun saveRun(DigestRun run) {
        runs.add(run);
        return run;
    }

    @Override
    public List<DigestRun> findRuns(String digestId, int limit) {
        return findRuns(digestId, limit, 0);
    }

    @Override
    public List<DigestRun> findRuns(String digestId, int limit, int offset) {
        return runs.stream()
                .filter(r -> digestId.equals(r.digestId()))
                .sorted(Comparator.comparing(DigestRun::ranAt).reversed())
                .skip(Math.max(offset, 0))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<DigestRun> findRun(String runId) {
        return runs.stream().filter(r -> r.id().equals(runId)).findFirst();
    }

    @Override
    public Optional<DigestRun> findLatestRun(String digestId) {
        return findRuns(digestId, 1).stream().findFirst();
    }

    @Override
    public Set<String> reportedEntityIds(String digestId) {
        return reportedEntityIds(digestId, null);
    }

    @Override
    public Set<String> reportedEntityIds(String digestId, Instant since) {
        Set<String> out = new LinkedHashSet<>();
        for (DigestRun run : runs) {
            if (digestId.equals(run.digestId())
                    && (since == null || !run.ranAt().isBefore(since))) {
                run.items().forEach(item -> {
                    if (item.entityId() != null) {
                        out.add(item.entityId());
                    }
                });
            }
        }
        return out;
    }

    @Override
    public void deleteRuns(String digestId) {
        runs.removeIf(r -> digestId.equals(r.digestId()));
    }
}
