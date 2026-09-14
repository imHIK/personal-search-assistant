package io.personalassistant.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The compact {@code "10/1s,500/1m"} syntax is the only way an operator states a limit in
 * {@code application.properties}, so a misread here is a limit that looks configured and is not.
 */
class RateLimitRulesTest {

    @Test
    void parsesEveryWindowUnit() {
        RateLimitPolicy policy = RateLimitRules.parse("10/30s,500/1m,2000/2h,10000/1d");

        assertEquals(4, policy.rules().size());
        assertEquals(new RateLimitRule(10, 30), policy.rules().get(0));
        assertEquals(Duration.ofMinutes(1), policy.rules().get(1).window());
        assertEquals(Duration.ofHours(2), policy.rules().get(2).window());
        assertEquals(Duration.ofDays(1), policy.rules().get(3).window());
    }

    @Test
    void blankOrNullMeansUnlimited() {
        assertTrue(RateLimitRules.parse(null).isUnlimited());
        assertTrue(RateLimitRules.parse("").isUnlimited());
        assertTrue(RateLimitRules.parse("   ").isUnlimited());
    }

    @Test
    void toleratesWhitespaceAndTrailingCommas() {
        assertEquals(2, RateLimitRules.parse(" 10/1s , 500/1m , ").rules().size());
    }

    /** A bare number would have to guess a unit, and a guessed window is a wrong limit. */
    @Test
    void rejectsAWindowWithNoUnit() {
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("10/60"));
    }

    @Test
    void rejectsMalformedRules() {
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("10"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("/1m"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("10/"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("abc/1m"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("10/1y"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitRules.parse("0/1m"));
    }

    @Test
    void formatsBackToTheLargestExactUnit() {
        assertEquals("10/1s,500/1m,10000/1d", RateLimitRules.parse("10/1s,500/60s,10000/24h").toString());
        assertEquals("5/90s", RateLimitRules.parse("5/90s").toString());
    }
}
