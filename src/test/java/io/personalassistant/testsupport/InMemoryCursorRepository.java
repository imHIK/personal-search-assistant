package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.storage.repository.CursorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link CursorRepository} mirroring the Mongo adapter's claim/lease semantics. */
public class InMemoryCursorRepository implements CursorRepository {

    public final Map<String, Cursor> store = new LinkedHashMap<>();

    @Override
    public boolean insertIfAbsent(Cursor cursor) {
        return store.putIfAbsent(cursor.id(), cursor) == null;
    }

    @Override
    public Optional<Cursor> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Cursor> findByKnowledge(String knowledgeId) {
        return store.values().stream().filter(c -> c.knowledgeId().equals(knowledgeId)).toList();
    }

    @Override
    public List<Cursor> findClaimable(int limit) {
        Instant now = Instant.now();
        // Mirror the Mongo adapter: least-recently-run first, never-run (null lastRunAt) first.
        return store.values().stream()
                .filter(c -> isClaimable(c, now))
                .sorted(Comparator.comparing(
                        (Cursor c) -> c.stats().lastRunAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<Cursor> claim(String cursorId, String owner, Duration leaseDuration) {
        Cursor c = store.get(cursorId);
        if (c == null || !isClaimable(c, Instant.now())) {
            return Optional.empty();
        }
        Cursor leased = with(c, CursorStatus.IN_PROGRESS,
                new Cursor.Lease(owner, Instant.now().plus(leaseDuration)), c.position(), c.retry(), c.stats());
        store.put(cursorId, leased);
        return Optional.of(leased);
    }

    @Override
    public void renewLease(String cursorId, String owner, Instant newExpiry) {
        Cursor c = store.get(cursorId);
        if (c != null && c.lease() != null && c.lease().owner().equals(owner)) {
            store.put(cursorId, with(c, c.status(), new Cursor.Lease(owner, newExpiry),
                    c.position(), c.retry(), c.stats()));
        }
    }

    @Override
    public boolean advancePosition(String cursorId, String owner, CursorPosition position,
                                   long fetchedDelta, Instant lastRunAt, Instant newExpiry) {
        Cursor c = store.get(cursorId);
        if (!ownsLiveLease(c, owner)) {
            return false;
        }
        Cursor.Stats stats = new Cursor.Stats(lastRunAt, c.stats().fetched() + fetchedDelta);
        store.put(cursorId, with(c, c.status(), new Cursor.Lease(owner, newExpiry), position, c.retry(), stats));
        return true;
    }

    @Override
    public boolean release(String cursorId, String owner, CursorStatus restingStatus) {
        Cursor c = store.get(cursorId);
        if (!ownsLiveLease(c, owner)) {
            return false;
        }
        store.put(cursorId, with(c, restingStatus, null, c.position(), c.retry(), c.stats()));
        return true;
    }

    @Override
    public boolean recordFailure(String cursorId, String owner, CursorStatus restingStatus, int retryCount,
                                 String lastError) {
        Cursor c = store.get(cursorId);
        if (!ownsLiveLease(c, owner)) {
            return false;
        }
        store.put(cursorId, with(c, restingStatus, null, c.position(),
                new Cursor.Retry(retryCount, lastError), c.stats()));
        return true;
    }

    /** Lease fence: the caller must still hold a live lease on the cursor. */
    private static boolean ownsLiveLease(Cursor c, String owner) {
        return c != null && c.lease() != null && owner.equals(c.lease().owner())
                && c.lease().isLiveAt(Instant.now());
    }

    @Override
    public int armForwardCursors(String knowledgeId) {
        int armed = 0;
        for (Cursor c : new ArrayList<>(store.values())) {
            if (c.knowledgeId().equals(knowledgeId) && c.direction() == CursorDirection.FORWARD
                    && c.status() == CursorStatus.IDLE) {
                store.put(c.id(), with(c, CursorStatus.AVAILABLE, c.lease(), c.position(), c.retry(), c.stats()));
                armed++;
            }
        }
        return armed;
    }

    @Override
    public int suspendByKnowledge(String knowledgeId) {
        int parked = 0;
        for (Cursor c : new ArrayList<>(store.values())) {
            if (c.knowledgeId().equals(knowledgeId)
                    && (c.status() == CursorStatus.AVAILABLE || c.status() == CursorStatus.IDLE)) {
                store.put(c.id(), with(c, CursorStatus.SUSPENDED, c.lease(), c.position(), c.retry(), c.stats()));
                parked++;
            }
        }
        return parked;
    }

    @Override
    public int resumeByKnowledge(String knowledgeId) {
        int armed = 0;
        for (Cursor c : new ArrayList<>(store.values())) {
            if (c.knowledgeId().equals(knowledgeId) && c.status() == CursorStatus.SUSPENDED) {
                store.put(c.id(), with(c, CursorStatus.AVAILABLE, c.lease(), c.position(), c.retry(), c.stats()));
                armed++;
            }
        }
        return armed;
    }

    @Override
    public boolean retire(String cursorId) {
        Cursor c = store.get(cursorId);
        if (c == null || c.status() == CursorStatus.IN_PROGRESS) {
            return false;
        }
        store.put(cursorId, with(c, CursorStatus.RETIRED, null, c.position(), c.retry(), c.stats()));
        return true;
    }

    @Override
    public boolean revive(String cursorId, Map<String, Object> attributes) {
        Cursor c = store.get(cursorId);
        if (c == null || c.status() != CursorStatus.RETIRED) {
            return false;
        }
        store.put(cursorId, new Cursor(c.id(), c.knowledgeId(), c.iterableId(),
                attributes == null ? c.attributes() : attributes, c.direction(),
                CursorPosition.start(), CursorStatus.AVAILABLE, null, Cursor.Retry.zero(),
                c.stats(), c.scope()));
        return true;
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        store.values().removeIf(c -> c.knowledgeId().equals(knowledgeId));
    }

    private static boolean isClaimable(Cursor c, Instant now) {
        if (c.status() == CursorStatus.AVAILABLE) {
            return true;
        }
        return c.status() == CursorStatus.IN_PROGRESS && !c.hasLiveLease(now);
    }

    private static Cursor with(Cursor c, CursorStatus status, Cursor.Lease lease, CursorPosition position,
                               Cursor.Retry retry, Cursor.Stats stats) {
        return new Cursor(c.id(), c.knowledgeId(), c.iterableId(), c.attributes(), c.direction(), position,
                status, lease, retry, stats, c.scope());
    }
}
