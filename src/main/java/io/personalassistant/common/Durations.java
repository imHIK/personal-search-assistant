package io.personalassistant.common;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free parser for the human-friendly duration strings used in schedule settings
 * and config (e.g. {@code "1d"}, {@code "6h"}, {@code "15m"}, {@code "30s"}). Also accepts an
 * ISO-8601 duration ({@code "PT15M"}, {@code "P1D"}) so callers can be explicit when they want to.
 *
 * <p>Deliberately tiny — it covers the single-unit shorthand the product uses for sync cadence; it
 * is <em>not</em> a general compound-duration grammar. Unknown/blank input yields {@code null} so
 * callers can treat "no interval set" uniformly.
 */
public final class Durations {

    private static final Pattern SHORTHAND = Pattern.compile("(?i)^\\s*(\\d+)\\s*(ms|s|m|h|d)\\s*$");

    private Durations() {
    }

    /**
     * Parse a shorthand or ISO-8601 duration. Returns {@code null} for {@code null}/blank input.
     *
     * @throws IllegalArgumentException if the value is non-blank but unparseable
     */
    public static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        char c0 = v.charAt(0);
        if (c0 == 'P' || c0 == 'p') {
            return Duration.parse(v); // ISO-8601, e.g. PT15M / P1D
        }
        Matcher m = SHORTHAND.matcher(v);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unparseable duration: '" + value
                    + "' (use e.g. 30s, 15m, 6h, 1d, or an ISO-8601 value like PT15M)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase()) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Unparseable duration: " + value);
        };
    }
}
