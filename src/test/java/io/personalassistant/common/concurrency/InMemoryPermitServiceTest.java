package io.personalassistant.common.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPermitServiceTest {

    private InMemoryPermitService permits;

    @BeforeEach
    void setUp() {
        permits = new InMemoryPermitService();
        permits.ttlSeconds = 120;
    }

    @Test
    void enforcesScopeCeiling() {
        assertTrue(permits.tryAcquire("global", 2, "w1").isPresent());
        assertTrue(permits.tryAcquire("global", 2, "w2").isPresent());
        assertFalse(permits.tryAcquire("global", 2, "w3").isPresent(), "third should exceed the ceiling");
        assertEquals(2, permits.liveCount("global"));
    }

    @Test
    void releaseFreesCapacity() {
        Optional<Permit> first = permits.tryAcquire("connector:SLACK", 1, "w1");
        assertTrue(first.isPresent());
        assertFalse(permits.tryAcquire("connector:SLACK", 1, "w2").isPresent());

        permits.release(first.get());
        assertTrue(permits.tryAcquire("connector:SLACK", 1, "w3").isPresent());
    }

    @Test
    void compositeAcquireIsAllOrNothing() {
        // Fill the connector scope so the composite acquire must fail without touching global.
        permits.tryAcquire("connector:SLACK", 1, "filler");

        List<ScopeLimit> limits = List.of(
                new ScopeLimit("global", 5),
                new ScopeLimit("connector:SLACK", 1));
        assertFalse(permits.tryAcquire(limits, "w1").isPresent());
        assertEquals(0, permits.liveCount("global"), "global must not be charged on a failed composite acquire");
    }

    @Test
    void compositeAcquireOccupiesAllScopes() {
        List<ScopeLimit> limits = List.of(new ScopeLimit("global", 5), new ScopeLimit("knowledge:k1", 2));
        Optional<Permit> permit = permits.tryAcquire(limits, "w1");
        assertTrue(permit.isPresent());
        assertEquals(1, permits.liveCount("global"));
        assertEquals(1, permits.liveCount("knowledge:k1"));

        permits.release(permit.get());
        assertEquals(0, permits.liveCount("global"));
        assertEquals(0, permits.liveCount("knowledge:k1"));
    }
}
