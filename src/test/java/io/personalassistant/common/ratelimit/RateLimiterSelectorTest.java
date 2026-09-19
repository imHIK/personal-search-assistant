package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.testsupport.RecordingRateLimiter;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The store switch. Hand-wired through {@link RateLimiterSelector#select} so no CDI container or Redis is
 * needed; the suppliers stand in for the lazy {@code Instance} lookups.
 */
class RateLimiterSelectorTest {

    private final RateLimiter memory = new RecordingRateLimiter();
    private final RateLimiter redis = new RecordingRateLimiter();

    private static Supplier<RateLimiter> mustNotBeBuilt() {
        return () -> {
            throw new AssertionError("the store that was not selected must not be instantiated");
        };
    }

    @Test
    void memorySelectsTheInProcessStore() {
        assertSame(memory, RateLimiterSelector.select("memory", () -> memory, mustNotBeBuilt()));
    }

    /**
     * Not building the Redis bean in memory mode is what lets tests and a Redis-less setup run: its data
     * source is never touched, so nothing tries to connect.
     */
    @Test
    void redisSelectsTheRedisStoreWithoutBuildingTheOther() {
        assertSame(redis, RateLimiterSelector.select("redis", mustNotBeBuilt(), () -> redis));
    }

    @Test
    void theValueIsTrimmedAndCaseInsensitive() {
        assertSame(redis, RateLimiterSelector.select(" Redis ", mustNotBeBuilt(), () -> redis));
    }

    /**
     * A typo must fail startup rather than fall back to memory — falling back silently is the restart
     * bug this switch exists to remove, with nothing in the logs to say so.
     */
    @Test
    void anUnknownStoreFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RateLimiterSelector.select("mongo", mustNotBeBuilt(), mustNotBeBuilt()));

        assertTrue(e.getMessage().contains("memory") && e.getMessage().contains("redis"),
                "the error must name the valid stores: " + e.getMessage());
    }
}
