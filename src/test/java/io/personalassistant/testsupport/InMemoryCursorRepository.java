package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.storage.repository.CursorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link CursorRepository} mirroring the Mongo adapter's claim/lease semantics. */
public class InMemoryCursorRepository implements CursorRepository {

    public final Map<String, Cursor> store = new LinkedHashMap<>();

    @Override
    public void insertIfAbsent(Cursor cursor) {
        store.putIfAbsent(cursor.id(), cursor);
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
        List<Cursor> out = new ArrayList<>();
        for (Cursor c : store.values()) {
            if (isClaimable(c, now)) {
                out.add(c);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
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
    public void advancePosition(String cursorId, String position, long fetchedDelta, Instant lastRunAt) {
        Cursor c = store.get(cursorId);
        if (c != null) {
            Cursor.Stats stats = new Cursor.Stats(lastRunAt, c.stats().fetched() + fetchedDelta);
            store.put(cursorId, with(c, c.status(), c.lease(), position, c.retry(), stats));
        }
    }

    @Override
    public void release(String cursorId, CursorStatus restingStatus) {
        Cursor c = store.get(cursorId);
        if (c != null) {
            store.put(cursorId, with(c, restingStatus, null, c.position(), c.retry(), c.stats()));
        }
    }

    @Override
    public void recordFailure(String cursorId, CursorStatus restingStatus, int retryCount) {
        Cursor c = store.get(cursorId);
        if (c != null) {
            store.put(cursorId, with(c, restingStatus, null, c.position(), new Cursor.Retry(retryCount), c.stats()));
        }
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
    public void deleteByKnowledge(String knowledgeId) {
        store.values().removeIf(c -> c.knowledgeId().equals(knowledgeId));
    }

    private static boolean isClaimable(Cursor c, Instant now) {
        if (c.status() == CursorStatus.AVAILABLE) {
            return true;
        }
        return c.status() == CursorStatus.IN_PROGRESS && !c.hasLiveLease(now);
    }

    private static Cursor with(Cursor c, CursorStatus status, Cursor.Lease lease, String position,
                               Cursor.Retry retry, Cursor.Stats stats) {
        return new Cursor(c.id(), c.knowledgeId(), c.iterableId(), c.direction(), position,
                status, lease, retry, stats, c.scope());
    }
}
