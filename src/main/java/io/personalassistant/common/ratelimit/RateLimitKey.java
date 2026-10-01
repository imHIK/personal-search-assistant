package io.personalassistant.common.ratelimit;

public record RateLimitKey(String value) {

    public RateLimitKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rate limit key must not be blank");
        }
    }

    /** One account's quota, shared by every knowledge bound to it. */
    public static RateLimitKey connection(String connectionId) {
        return new RateLimitKey("connection:" + connectionId);
    }

    /** A public job board has no credential, so the platform is the bucket. */
    public static RateLimitKey board(String platform) {
        return new RateLimitKey("board:" + platform);
    }

    public static RateLimitKey embedding(String providerId) {
        return new RateLimitKey("embedding:" + providerId);
    }

    @Override
    public String toString() {
        return value;
    }
}
