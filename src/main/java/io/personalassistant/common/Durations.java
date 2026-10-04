package io.personalassistant.common;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {

    private static final Pattern SHORTHAND = Pattern.compile("(?i)^\\s*(\\d+)\\s*(ms|s|m|h|d)\\s*$");

    private Durations() {
    }

    /**
     * Single-unit shorthand ({@code 1d}, {@code 15m}, {@code 30s}) or ISO-8601; null or blank is null.
     *
     * @throws IllegalArgumentException if non-blank but unparseable
     */
    public static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        char c0 = v.charAt(0);
        if (c0 == 'P' || c0 == 'p') {
            return Duration.parse(v);
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
