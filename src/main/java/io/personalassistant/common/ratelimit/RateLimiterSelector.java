package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the single active {@link RateLimiter} — the {@code @Default} bean {@code OutboundHttp} injects
 * — from {@code app.ratelimit.store}. Same shape as {@code EmbeddingProviderSelector}: both stores carry
 * {@link ProviderImpl}, so neither is injectable on its own.
 *
 * <p>The lookups are lazy, and that is load-bearing: in {@code memory} mode the Redis store is never
 * constructed, so a test or a Redis-less setup never issues a Redis command.
 *
 * <p>An unknown value fails startup rather than defaulting to memory. A silent memory fallback is the
 * restart bug this switch exists to remove, and it would look exactly like a working limiter.
 */
@ApplicationScoped
public class RateLimiterSelector {

    private static final Logger LOG = Logger.getLogger(RateLimiterSelector.class.getName());

    static final String MEMORY = "memory";
    static final String REDIS = "redis";

    @Produces
    @ApplicationScoped
    public RateLimiter active(@ProviderImpl Instance<SlidingWindowRateLimiter> memory,
                              @ProviderImpl Instance<RedisRateLimiter> redis,
                              @ConfigProperty(name = "app.ratelimit.store", defaultValue = "redis") String store) {
        RateLimiter selected = select(store, memory::get, redis::get);
        LOG.info("Active rate-limit store: " + normalize(store));
        return selected;
    }

    /** Extracted so the switch is testable without a CDI container. */
    static RateLimiter select(String store, Supplier<? extends RateLimiter> memory,
                              Supplier<? extends RateLimiter> redis) {
        return switch (normalize(store)) {
            case MEMORY -> memory.get();
            case REDIS -> redis.get();
            default -> throw new IllegalStateException("Unknown app.ratelimit.store '" + store
                    + "'. Set it to one of: [" + MEMORY + ", " + REDIS + "]");
        };
    }

    private static String normalize(String store) {
        return store == null ? "" : store.trim().toLowerCase(Locale.ROOT);
    }
}
