package io.personalassistant.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesShorthandUnits() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMinutes(15), Durations.parse("15m"));
        assertEquals(Duration.ofHours(6), Durations.parse("6h"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d"));
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms"));
    }

    @Test
    void toleratesWhitespaceAndCase() {
        assertEquals(Duration.ofMinutes(15), Durations.parse("  15 M "));
    }

    @Test
    void parsesIso8601() {
        assertEquals(Duration.ofMinutes(15), Durations.parse("PT15M"));
        assertEquals(Duration.ofDays(1), Durations.parse("P1D"));
    }

    @Test
    void nullAndBlankYieldNull() {
        assertNull(Durations.parse(null));
        assertNull(Durations.parse("   "));
    }

    @Test
    void unparseableThrows() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("10x"));
    }
}
