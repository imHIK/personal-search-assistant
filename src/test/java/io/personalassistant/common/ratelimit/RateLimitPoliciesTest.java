package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.personalassistant.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitPoliciesTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private RateLimitPolicies policies;
    private MutableClock clock;
    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        policies = RateLimitPolicies.unlimited();
        policies.embeddingRules = Optional.of("4/1m");
        policies.embeddingBackgroundRules = Optional.of("2/1m");
        policies.llmRules = Optional.empty();
        policies.llmBackgroundRules = Optional.empty();

        clock = new MutableClock(T0);
        limiter = new SlidingWindowRateLimiter();
        limiter.clock = clock;
        limiter.maxWaitSeconds = 0; // defer at once, so a WAIT refusal is observable as a throw
        limiter.maxPenaltySeconds = 21600;
        limiter.waiter = clock::advance;
    }

    @Test
    void aBackgroundCallGetsTheLowerCeilingOnTheSameKey() {
        RateLimit background = policies.forEmbedding("gemini", RateLimitMode.WAIT);
        RateLimit search = policies.forEmbedding("gemini", RateLimitMode.FAIL_FAST);

        assertEquals(background.key(), search.key(), "one key is what makes it one counter");
        assertEquals(RateLimitRules.parse("2/1m"), background.policy());
        assertEquals(RateLimitRules.parse("4/1m"), search.policy());
    }

    @Test
    void anUnsetBackgroundRuleInheritsTheSharedOne() {
        policies.embeddingBackgroundRules = Optional.empty();

        assertEquals(RateLimitRules.parse("4/1m"),
                policies.forEmbedding("gemini", RateLimitMode.WAIT).policy());
    }

    @Test
    void indexingStopsAtItsCeilingWhileSearchesSpendTheReserve() {
        RateLimit background = policies.forEmbedding("gemini", RateLimitMode.WAIT);
        RateLimit search = policies.forEmbedding("gemini", RateLimitMode.FAIL_FAST);

        limiter.acquire(background);
        limiter.acquire(background);
        assertThrows(RateLimitedException.class, () -> limiter.acquire(background),
                "indexing has used its share");

        limiter.acquire(search);
        limiter.acquire(search);
        assertThrows(RateLimitedException.class, () -> limiter.acquire(search),
                "searches share the counter, so the reserve is the gap, not a fresh 4");
    }

    @Test
    void aBackgroundRetryAtWaitsOutTheSurplusSearchesSpent() {
        RateLimit background = policies.forEmbedding("gemini", RateLimitMode.WAIT);
        RateLimit search = policies.forEmbedding("gemini", RateLimitMode.FAIL_FAST);

        limiter.acquire(background);
        clock.advance(Duration.ofSeconds(1));
        limiter.acquire(background);
        clock.advance(Duration.ofSeconds(1));
        limiter.acquire(search);
        clock.advance(Duration.ofSeconds(1));
        limiter.acquire(search);

        RateLimitedException deferred = assertThrows(RateLimitedException.class,
                () -> limiter.acquire(background));

        // Four in the window against the background ceiling of 2: it must fall to 1, so the T0+2s admission
        // must age out.
        assertEquals(T0.plusSeconds(62), deferred.retryAt());
    }

    @Test
    void aLowerCeilingCreatingTheWindowDoesNotCapAHigherOne() {
        RateLimit background = policies.forEmbedding("gemini", RateLimitMode.WAIT);
        RateLimit search = policies.forEmbedding("gemini", RateLimitMode.FAIL_FAST);

        limiter.acquire(background);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(search);
        }
        assertEquals(0, limiter.availableNow(search.key(), new RateLimitRule(4, 60)));
    }
}
