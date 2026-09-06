package io.personalassistant.common.ratelimit;

import java.util.List;

/**
 * The complete set of ceilings a caller must satisfy — a request is admitted only when
 * <strong>every</strong> rule has a token, and it then spends one from each. That all-or-nothing
 * acquisition is what lets {@code 10/1s, 500/1m, 10000/1d} coexist without a call spending its
 * per-second allowance and then stalling on the daily one.
 *
 * <p>An empty rule list means unlimited, which is both the shipped default and how a user clears a
 * limit they previously set: {@code null} already means "unchanged" on the PATCH path, so an explicit
 * empty list is the only way to express removal.
 *
 * @param rules the ceilings to enforce together; empty means no limit
 */
public record RateLimitPolicy(List<RateLimitRule> rules) {

    /** No ceilings at all — every call is admitted immediately. */
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
