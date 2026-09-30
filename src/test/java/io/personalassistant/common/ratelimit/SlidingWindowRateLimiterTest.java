package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The waiter advances the fake clock instead of sleeping, so a wait is an exact assertion rather than a
 * timing race.
 */
class SlidingWindowRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final RateLimitKey KEY = RateLimitKey.connection("conn_1");

    private MutableClock clock;
    private SlidingWindowRateLimiter limiter;
    private List<Duration> waits;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        waits = new ArrayList<>();
        limiter = new SlidingWindowRateLimiter();
        limiter.clock = clock;
        limiter.maxWaitSeconds = 60;
        limiter.maxPenaltySeconds = 21600;
        limiter.waiter = duration -> {
            waits.add(duration);
            clock.advance(duration);
        };
    }

    private static RateLimit limit(RateLimitMode mode, RateLimitRule... rules) {
        return new RateLimit(KEY, RateLimitPolicy.of(rules), mode);
    }

    @Test
    void admitsUpToThePermitsThenRefusesInFailFast() {
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, new RateLimitRule(2, 1));

        limiter.acquire(limit);
        limiter.acquire(limit);

        assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));
        assertTrue(waits.isEmpty(), "fail-fast must never sleep");
    }

    @Test
    void anUnlimitedPolicyAdmitsEverything() {
        RateLimit limit = new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.FAIL_FAST);

        for (int i = 0; i < 100; i++) {
            limiter.acquire(limit);
        }
    }

    @Test
    void aSlotReturnsOnlyWhenTheCallThatTookItAgesOut() {
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, new RateLimitRule(2, 1));
        limiter.acquire(limit);
        clock.advance(Duration.ofMillis(400));
        limiter.acquire(limit);

        clock.advance(Duration.ofMillis(600));

        limiter.acquire(limit);
        assertThrows(RateLimitedException.class, () -> limiter.acquire(limit),
                "only the first call's slot has aged out; the second still counts");
    }

    @Test
    void theWholeWindowReopensTogether() {
        RateLimitRule rule = new RateLimitRule(5, 60);
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, rule);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(limit);
        }

        clock.advance(Duration.ofSeconds(30));
        assertEquals(0, limiter.availableNow(KEY, rule), "a bucket would have dripped 2 back by now");

        clock.advance(Duration.ofSeconds(30));
        assertEquals(5, limiter.availableNow(KEY, rule));
        for (int i = 0; i < 5; i++) {
            limiter.acquire(limit);
        }
    }

    @Test
    void neverAdmitsMoreThanThePermitsInAnyTrailingWindow() {
        RateLimitRule rule = new RateLimitRule(3, 10);
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, rule);
        List<Instant> admissions = new ArrayList<>();

        for (int tick = 0; tick < 60; tick++) {
            try {
                limiter.acquire(limit);
                admissions.add(clock.instant());
            } catch (RateLimitedException expected) {
            }
            clock.advance(Duration.ofSeconds(1));
        }

        assertTrue(admissions.size() > 3, "the window must reopen, not latch shut");
        for (Instant at : admissions) {
            long inWindow = admissions.stream()
                    .filter(other -> !other.isAfter(at) && other.isAfter(at.minusSeconds(10)))
                    .count();
            assertTrue(inWindow <= 3, "trailing 10s at " + at + " held " + inWindow + " calls");
        }
    }

    @Test
    void waitsAndProceedsWhenTheWaitIsWithinBudget() {
        RateLimit limit = limit(RateLimitMode.WAIT, new RateLimitRule(1, 1));
        limiter.acquire(limit);

        limiter.acquire(limit);

        assertEquals(1, waits.size(), "the second call waited exactly once");
        assertEquals(Duration.ofSeconds(1), waits.get(0));
        assertEquals(T0.plusSeconds(1), clock.instant());
    }

    @Test
    void defersInsteadOfSleepingWhenTheWaitExceedsTheBudget() {
        RateLimit limit = limit(RateLimitMode.WAIT, new RateLimitRule(1, 86400));
        limiter.acquire(limit);

        RateLimitedException e = assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));

        assertTrue(waits.isEmpty(), "a day-long wait must not be slept through");
        assertEquals(T0.plusSeconds(86400), e.retryAt());
        assertEquals(KEY, e.key());
    }

    @Test
    void chargesEveryWindowOnlyWhenAllOfThemHaveRoom() {
        RateLimitRule perSecond = new RateLimitRule(10, 1);
        RateLimitRule perDay = new RateLimitRule(1, 86400);
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, perSecond, perDay);

        limiter.acquire(limit);
        assertEquals(9, limiter.availableNow(KEY, perSecond));

        assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));
        assertEquals(9, limiter.availableNow(KEY, perSecond),
                "the refused call spent nothing in the window that had room");
    }

    @Test
    void aPenaltyPausesEvenAnUnlimitedKey() {
        RateLimit limit = new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.FAIL_FAST);
        limiter.penalize(KEY, T0.plusSeconds(30));

        RateLimitedException e = assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));
        assertEquals(T0.plusSeconds(30), e.retryAt());

        clock.advance(Duration.ofSeconds(31));
        limiter.acquire(limit);
    }

    @Test
    void aPenaltyIsWaitedOutWhenItFitsTheBudget() {
        RateLimit limit = new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.WAIT);
        limiter.penalize(KEY, T0.plusSeconds(5));

        limiter.acquire(limit);

        assertEquals(List.of(Duration.ofSeconds(5)), waits);
    }

    @Test
    void anAbsurdPenaltyIsClampedToTheCeiling() {
        limiter.maxPenaltySeconds = 60;
        RateLimit limit = new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.FAIL_FAST);
        limiter.penalize(KEY, T0.plusSeconds(86_400));

        RateLimitedException e = assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));
        assertEquals(T0.plusSeconds(60), e.retryAt(), "the server's own answer is bounded, not obeyed");
    }

    @Test
    void concurrentPenaltiesKeepTheFurthestInstant() {
        RateLimit limit = new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.FAIL_FAST);
        limiter.penalize(KEY, T0.plusSeconds(60));
        limiter.penalize(KEY, T0.plusSeconds(10));

        RateLimitedException e = assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));
        assertEquals(T0.plusSeconds(60), e.retryAt(), "a shorter backoff must not shorten a longer one");
    }

    @Test
    void windowsAreIndependentPerKey() {
        RateLimitRule rule = new RateLimitRule(1, 1);
        RateLimit one = new RateLimit(RateLimitKey.connection("a"), RateLimitPolicy.of(rule),
                RateLimitMode.FAIL_FAST);
        RateLimit two = new RateLimit(RateLimitKey.connection("b"), RateLimitPolicy.of(rule),
                RateLimitMode.FAIL_FAST);

        limiter.acquire(one);
        limiter.acquire(two);

        assertThrows(RateLimitedException.class, () -> limiter.acquire(one));
    }

    @Test
    void aWindowThatIsAlreadyOpenCostsNoWait() {
        RateLimit limit = limit(RateLimitMode.WAIT, new RateLimitRule(5, 1));

        limiter.acquire(limit);

        assertTrue(waits.isEmpty());
        assertFalse(limit.policy().isUnlimited());
    }
}
