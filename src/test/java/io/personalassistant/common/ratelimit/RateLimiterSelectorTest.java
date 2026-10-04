package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.testsupport.RecordingRateLimiter;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

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

    @Test
    void redisSelectsTheRedisStoreWithoutBuildingTheOther() {
        assertSame(redis, RateLimiterSelector.select("redis", mustNotBeBuilt(), () -> redis));
    }

    @Test
    void theValueIsTrimmedAndCaseInsensitive() {
        assertSame(redis, RateLimiterSelector.select(" Redis ", mustNotBeBuilt(), () -> redis));
    }

    @Test
    void anUnknownStoreFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RateLimiterSelector.select("mongo", mustNotBeBuilt(), mustNotBeBuilt()));

        assertTrue(e.getMessage().contains("memory") && e.getMessage().contains("redis"),
                "the error must name the valid stores: " + e.getMessage());
    }
}
