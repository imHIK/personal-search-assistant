package io.personalassistant.common.ratelimit;

import java.util.List;

/**
 * A call is admitted only when every rule has room, and then spends from each. An empty list is unlimited,
 * and is how a user clears a limit, since null means unchanged on PATCH.
 */
public record RateLimitPolicy(List<RateLimitRule> rules) {

    public static final RateLimitPolicy UNLIMITED = new RateLimitPolicy(List.of());

    public RateLimitPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static RateLimitPolicy of(RateLimitRule... rules) {
        return new RateLimitPolicy(List.of(rules));
    }

    public boolean isUnlimited() {
        return rules.isEmpty();
    }

    /** The compact form {@code RateLimitRules} parses, e.g. {@code "10/1s,500/1m"}. */
    @Override
    public String toString() {
        return rules.stream().map(RateLimitRule::toString).reduce((a, b) -> a + "," + b).orElse("");
    }
}
