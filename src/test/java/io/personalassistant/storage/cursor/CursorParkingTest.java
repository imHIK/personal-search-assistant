package io.personalassistant.storage.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parking semantics ({@code suspendByKnowledge}/{@code resumeByKnowledge}) and least-recently-run
 * ordering of {@code findClaimable}. The in-memory repository mirrors the Mongo adapter, so these
 * assertions document the contract both implementations must honour.
 */
class CursorParkingTest {

    private InMemoryCursorRepository cursors;

    @BeforeEach
    void setUp() {
        cursors = new InMemoryCursorRepository();
    }

    private Cursor cursor(String id, String knowledgeId, CursorStatus status, Instant lastRunAt) {
        return new Cursor(id, knowledgeId, "iter", java.util.Map.of(), CursorDirection.FORWARD,
                CursorPosition.start(), status,
                status == CursorStatus.IN_PROGRESS
                        ? new Cursor.Lease("worker-x", Instant.now().plusSeconds(60)) : null,
                Cursor.Retry.zero(),
                new Cursor.Stats(lastRunAt, 0),
                new Cursor.Scope(SourceType.SLACK));
    }

    @Test
    void suspendParksAvailableAndIdleButLeavesInProgress() {
        cursors.insertIfAbsent(cursor("a", "k1", CursorStatus.AVAILABLE, null));
        cursors.insertIfAbsent(cursor("i", "k1", CursorStatus.IDLE, null));
        cursors.insertIfAbsent(cursor("p", "k1", CursorStatus.IN_PROGRESS, null));

        int parked = cursors.suspendByKnowledge("k1");

        assertEquals(2, parked, "AVAILABLE + IDLE parked; the leased one is left running");
        assertEquals(CursorStatus.SUSPENDED, cursors.findById("a").orElseThrow().status());
        assertEquals(CursorStatus.SUSPENDED, cursors.findById("i").orElseThrow().status());
        assertEquals(CursorStatus.IN_PROGRESS, cursors.findById("p").orElseThrow().status());
    }

    @Test
    void suspendIsScopedToOneKnowledge() {
        cursors.insertIfAbsent(cursor("a", "k1", CursorStatus.AVAILABLE, null));
        cursors.insertIfAbsent(cursor("b", "k2", CursorStatus.AVAILABLE, null));

        cursors.suspendByKnowledge("k1");

        assertEquals(CursorStatus.SUSPENDED, cursors.findById("a").orElseThrow().status());
        assertEquals(CursorStatus.AVAILABLE, cursors.findById("b").orElseThrow().status(),
                "another knowledge's cursors are untouched");
    }

    @Test
    void suspendedCursorsAreExcludedFromClaimable() {
        cursors.insertIfAbsent(cursor("a", "k1", CursorStatus.AVAILABLE, null));
        cursors.suspendByKnowledge("k1");

        assertTrue(cursors.findClaimable(100).isEmpty(), "parked cursors are not claimable");
    }

    @Test
    void resumeReArmsOnlySuspendedCursors() {
        cursors.insertIfAbsent(cursor("a", "k1", CursorStatus.SUSPENDED, null));
        cursors.insertIfAbsent(cursor("e", "k1", CursorStatus.EXHAUSTED, null));

        int armed = cursors.resumeByKnowledge("k1");

        assertEquals(1, armed);
        assertEquals(CursorStatus.AVAILABLE, cursors.findById("a").orElseThrow().status());
        assertEquals(CursorStatus.EXHAUSTED, cursors.findById("e").orElseThrow().status(),
                "terminal cursors are not resurrected by resume");
    }

    @Test
    void findClaimableOrdersByLastRunAtNeverRunFirst() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        // Insert out of order: recent, never-run, older.
        cursors.insertIfAbsent(cursor("recent", "k1", CursorStatus.AVAILABLE, t2));
        cursors.insertIfAbsent(cursor("neverRun", "k1", CursorStatus.AVAILABLE, null));
        cursors.insertIfAbsent(cursor("older", "k1", CursorStatus.AVAILABLE, t1));

        List<String> order = cursors.findClaimable(100).stream().map(Cursor::id).toList();

        assertEquals(List.of("neverRun", "older", "recent"), order,
                "never-run first, then least-recently-run");
    }

    @Test
    void findClaimableRespectsLimitAfterOrdering() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        cursors.insertIfAbsent(cursor("recent", "k1", CursorStatus.AVAILABLE, t2));
        cursors.insertIfAbsent(cursor("older", "k1", CursorStatus.AVAILABLE, t1));

        List<String> top1 = cursors.findClaimable(1).stream().map(Cursor::id).toList();

        assertEquals(List.of("older"), top1, "the limit keeps the oldest, not insertion order");
        assertFalse(top1.contains("recent"));
    }
}
