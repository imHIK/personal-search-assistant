package io.personalassistant.common.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and formats the compact {@code "10/1s,500/1m,10000/1d"} rule syntax.
 *
 * <p>Exists so a multi-window policy fits in one config property. The alternative — a property per rule
 * per area — multiplies keys without bound and cannot express "two windows for Gmail, three for Groq",
 * and {@code docs/configuration.md} keeps numeric knobs in {@code application.properties} rather than
 * promoting them to a JSON content file.
 */
public final class RateLimitRules {

    private RateLimitRules() {
    }

    /**
     * @param spec comma-separated {@code permits/window} pairs; blank or null means unlimited
     * @return the parsed policy
     * @throws IllegalArgumentException if any entry is malformed
     */
    public static RateLimitPolicy parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return RateLimitPolicy.UNLIMITED;
        }
        List<RateLimitRule> rules = new ArrayList<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                rules.add(parseRule(trimmed));
            }
        }
        return new RateLimitPolicy(rules);
    }

    private static RateLimitRule parseRule(String entry) {
        int slash = entry.indexOf('/');
        if (slash <= 0 || slash == entry.length() - 1) {
            throw new IllegalArgumentException(
                    "Malformed rate limit rule '" + entry + "'; expected <permits>/<window> e.g. 500/1m");
        }
        String permitsText = entry.substring(0, slash).trim();
        int permits;
        try {
            permits = Integer.parseInt(permitsText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Malformed permit count '" + permitsText + "' in rate limit rule '" + entry + "'", e);
        }
        return RateLimitRule.of(permits, parseWindow(entry.substring(slash + 1).trim(), entry));
    }

    /**
     * Accepts {@code 30s} / {@code 1m} / {@code 2h} / {@code 1d}. A bare number is rejected rather than
     * assumed to be seconds: a silently misread window is a limit that looks configured and is not.
     */
    private static Duration parseWindow(String window, String entry) {
        if (window.length() < 2) {
            throw new IllegalArgumentException(
                    "Malformed window '" + window + "' in rate limit rule '" + entry
                            + "'; expected a unit suffix s, m, h or d");
        }
        char unit = window.charAt(window.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(window.substring(0, window.length() - 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Malformed window '" + window + "' in rate limit rule '" + entry + "'", e);
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException(
                    "Unknown window unit '" + unit + "' in rate limit rule '" + entry
                            + "'; expected s, m, h or d");
        };
    }

    /** Inverse of {@link #parseWindow}, choosing the largest unit that divides the duration exactly. */
    public static String format(Duration window) {
        long seconds = window.toSeconds();
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + "d";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }
}
