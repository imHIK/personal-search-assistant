package io.personalassistant.common.ratelimit;

/**
 * Identifies the bucket a call is charged against. Mirrors {@code concurrency.ScopeLimit}'s
 * {@code "connector:SLACK"} convention so the two throttles read alike in logs.
 *
 * <p>The choice of key is the choice of what is being protected, and it is not always the account: an
 * account is the right unit for Gmail and Drive (the quota follows the credential), but the public job
 * boards have no {@code Connection} at all, so they are keyed by platform — one bucket shared by every
 * knowledge watching Greenhouse companies, which is exactly what the board sees.
 *
 * @param value the fully-qualified key, e.g. {@code "connection:conn_1"} or {@code "board:greenhouse"}
 */
public record RateLimitKey(String value) {

    public RateLimitKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rate limit key must not be blank");
        }
    }

    /** One account's quota, shared by every knowledge bound to that {@code Connection}. */
    public static RateLimitKey connection(String connectionId) {
        return new RateLimitKey("connection:" + connectionId);
    }

    /** One public job board's quota — no credential, so the platform is the unit. */
    public static RateLimitKey board(String platform) {
        return new RateLimitKey("board:" + platform);
    }

    public static RateLimitKey llm(String providerId) {
        return new RateLimitKey("llm:" + providerId);
    }

    public static RateLimitKey embedding(String providerId) {
        return new RateLimitKey("embedding:" + providerId);
    }

    @Override
    public String toString() {
        return value;
    }
}
