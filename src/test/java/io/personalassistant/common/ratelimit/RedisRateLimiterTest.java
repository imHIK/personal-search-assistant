package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedisRateLimiterTest {

    @Test
    void globEscapeMakesAKeyMatchOnlyItself() {
        assertEquals("connection:conn_1", RedisRateLimiter.globEscape("connection:conn_1"));
        assertEquals("board:a\\*b\\?c\\[d\\]e\\\\f", RedisRateLimiter.globEscape("board:a*b?c[d]e\\f"));
    }
}
