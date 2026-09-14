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
 * Behaviour of the rolling window, driven by a hand-moved clock so nothing here sleeps. The waiter is
 * replaced with one that advances that clock, which is what makes "the caller waited 400ms and then
 * proceeded" an exact assertion rather than a timing race.
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

    /**
     * The difference from a refilling bucket, and the reason for the swap: half a window buys nothing.
     * A slot comes back when the call that took it ages out, not at the sustained rate — which is what
     * keeps the count in any trailing window at or under the permits a server counting the same way
     * sees.
     */
    @Test
    void aSlotReturnsOnlyWhenTheCallThatTookItAgesOut() {
        RateLimit limit = limit(RateLimitMode.FAIL_FAST, new RateLimitRule(2, 1));
        limiter.acquire(limit);
        clock.advance(Duration.ofMillis(400));
        limiter.acquire(limit);

        clock.advance(Duration.ofMillis(600)); // 1.0s since the first call, 0.6s since the second

        limiter.acquire(limit);
        assertThrows(RateLimitedException.class, () -> limiter.acquire(limit),
                "only the first call's slot has aged out; the second still counts");
    }

    /** The whole window comes back at once, a window after it was spent — not a permit at a time. */
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

    /**
     * The invariant a rolling window exists to hold, and the one a bucket breaks: spread calls out as
     * far as the limiter will allow and no trailing window ever holds more than the rule permits. At
     * {@code 3/10s} a bucket would admit a 4th call 3.3s in, which a server counting the trailing 10s
     * would answer with a 429.
     */
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
                // still inside the window; the assertion below is what matters
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

    /**
     * The case the whole design turns on: a daily quota must not park a scheduler thread for a day. The
     * caller is handed the reopening instant instead, to persist and resume from.
     */
    @Test
    void defersInsteadOfSleepingWhenTheWaitExceedsTheBudget() {
        RateLimit limit = limit(RateLimitMode.WAIT, new RateLimitRule(1, 86400));
        limiter.acquire(limit);

        RateLimitedException e = assertThrows(RateLimitedException.class, () -> limiter.acquire(limit));

        assertTrue(waits.isEmpty(), "a day-long wait must not be slept through");
        assertEquals(T0.plusSeconds(86400), e.retryAt());
        assertEquals(KEY, e.key());
    }

    /** All-or-nothing: a blocked long window must not consume the short window's token. */
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

    /**
     * A server saying "stop" outranks our absence of a local guess, so a penalty binds even where no
     * policy is configured — otherwise an unconfigured board would keep hammering a service that 429'd.
     */
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

    /**
     * {@code Retry-After} is remote input, and the instant it produces is load-bearing: a rate-limited
     * cursor is held out of the ingestion batch until it passes. A misparsed or hostile value must not
     * be able to park work for a week, so the pause is capped here rather than trusted by each caller.
     */
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
