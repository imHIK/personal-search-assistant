package io.personalassistant.common.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPermitServiceTest {

    private static final Duration TTL = Duration.ofSeconds(120);

    private InMemoryPermitService permits;

    @BeforeEach
    void setUp() {
        permits = new InMemoryPermitService();
    }

    @Test
    void enforcesScopeCeiling() {
        assertTrue(permits.tryAcquire("global", 2, "w1", TTL).isPresent());
        assertTrue(permits.tryAcquire("global", 2, "w2", TTL).isPresent());
        assertFalse(permits.tryAcquire("global", 2, "w3", TTL).isPresent(), "third should exceed the ceiling");
        assertEquals(2, permits.liveCount("global"));
    }

    @Test
    void releaseFreesCapacity() {
        Optional<Permit> first = permits.tryAcquire("connector:SLACK", 1, "w1", TTL);
        assertTrue(first.isPresent());
        assertFalse(permits.tryAcquire("connector:SLACK", 1, "w2", TTL).isPresent());

        permits.release(first.get());
        assertTrue(permits.tryAcquire("connector:SLACK", 1, "w3", TTL).isPresent());
    }

    @Test
    void compositeAcquireIsAllOrNothing() {
        // Fill the connector scope so the composite acquire must fail without touching global.
        permits.tryAcquire("connector:SLACK", 1, "filler", TTL);

        List<ScopeLimit> limits = List.of(
                new ScopeLimit("global", 5),
                new ScopeLimit("connector:SLACK", 1));
        assertFalse(permits.tryAcquire(limits, "w1", TTL).isPresent());
        assertEquals(0, permits.liveCount("global"), "global must not be charged on a failed composite acquire");
    }

    @Test
    void compositeAcquireOccupiesAllScopes() {
        List<ScopeLimit> limits = List.of(new ScopeLimit("global", 5), new ScopeLimit("knowledge:k1", 2));
        Optional<Permit> permit = permits.tryAcquire(limits, "w1", TTL);
        assertTrue(permit.isPresent());
        assertEquals(1, permits.liveCount("global"));
        assertEquals(1, permits.liveCount("knowledge:k1"));

        permits.release(permit.get());
        assertEquals(0, permits.liveCount("global"));
        assertEquals(0, permits.liveCount("knowledge:k1"));
    }

    @Test
    void permitCarriesTheAcquiredTtl() {
        // The caller's TTL is honoured per acquire and carried on the permit (so renew uses it),
        // rather than the service imposing one shared value on every stage.
        Duration ingestionTtl = Duration.ofSeconds(900);
        Permit permit = permits.tryAcquire("global", 1, "w1", ingestionTtl).orElseThrow();

        assertEquals(ingestionTtl, permit.ttl());
        assertEquals(permit.expiresAt(), permit.expiresAt()); // sanity: expiry set
        assertTrue(permit.expiresAt().isAfter(java.time.Instant.now().plusSeconds(800)),
                "expiry reflects the acquired TTL, not a fixed default");
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> permits.tryAcquire("global", 1, "w1", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> permits.tryAcquire("global", 1, "w1", Duration.ofSeconds(-1)));
    }
}
