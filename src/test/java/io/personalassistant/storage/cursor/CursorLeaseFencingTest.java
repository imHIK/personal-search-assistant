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
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CursorLeaseFencingTest {

    private InMemoryCursorRepository cursors;
    private String cursorId;

    @BeforeEach
    void setUp() {
        cursors = new InMemoryCursorRepository();
        Cursor c = TestData.cursor("kn_1", "root", CursorDirection.BACKWARD, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(c);
        cursorId = c.id();
    }

    @Test
    void staleWorkerCannotAdvanceOrRelease() {
        cursors.claim(cursorId, "w1", Duration.ofMinutes(5)).orElseThrow();
        Instant expiry = Instant.now().plusSeconds(60);

        assertFalse(cursors.advancePosition(cursorId, "w2", CursorPosition.of(Map.of("seq", 9L)), 5, Instant.now(), expiry));
        assertFalse(cursors.release(cursorId, "w2", CursorStatus.AVAILABLE));
        assertFalse(cursors.recordFailure(cursorId, "w2", CursorStatus.FAILED, 3, "boom", null));

        Cursor after = cursors.findById(cursorId).orElseThrow();
        assertTrue(after.position().isStart(), "stale worker must not move the position");
        assertEquals(CursorStatus.IN_PROGRESS, after.status(), "stale worker must not change status");
        assertEquals("w1", after.lease().owner(), "lease still belongs to the real owner");
    }

    @Test
    void rightfulOwnerCanAdvanceAndRelease() {
        cursors.claim(cursorId, "w1", Duration.ofMinutes(5)).orElseThrow();
        Instant expiry = Instant.now().plusSeconds(60);

        assertTrue(cursors.advancePosition(cursorId, "w1", CursorPosition.of(Map.of("seq", 1L)), 2, Instant.now(), expiry));
        assertEquals(1L, cursors.findById(cursorId).orElseThrow().position().getLong("seq", 0L));

        assertTrue(cursors.release(cursorId, "w1", CursorStatus.EXHAUSTED));
        assertEquals(CursorStatus.EXHAUSTED, cursors.findById(cursorId).orElseThrow().status());
    }

    @Test
    void newOwnerCanReclaimAfterLeaseExpiryAndOldOwnerIsFencedOut() {
        cursors.claim(cursorId, "w1", Duration.ofMillis(1)).orElseThrow();
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Cursor reclaimed = cursors.claim(cursorId, "w2", Duration.ofMinutes(5)).orElseThrow();
        assertEquals("w2", reclaimed.lease().owner());

        Instant expiry = Instant.now().plusSeconds(60);
        assertFalse(cursors.advancePosition(cursorId, "w1", CursorPosition.of(Map.of("seq", 7L)), 1, Instant.now(), expiry));
        assertTrue(cursors.advancePosition(cursorId, "w2", CursorPosition.of(Map.of("seq", 2L)), 1, Instant.now(), expiry));
        assertEquals(2L, cursors.findById(cursorId).orElseThrow().position().getLong("seq", 0L));
    }
}
