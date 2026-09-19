package io.personalassistant.common;

import jakarta.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a concrete adapter chosen by config (an {@code EmbeddingProvider} or {@code LlmProvider}
 * implementation, or a {@code RateLimiter} store) as a <em>candidate</em> — one of possibly several —
 * rather than the bean that callers inject directly.
 *
 * <p>Adding this qualifier removes the implicit {@code @Default} from the bean, so no single
 * implementation is injectable on its own. Instead a selector produces the active {@code @Default}
 * bean by reading a config key (see {@code EmbeddingProviderSelector} / {@code LlmProviderSelector} /
 * {@code RateLimiterSelector}). This mirrors the connector/parser registry pattern: adding a new
 * model is a new {@code @ProviderImpl} bean plus a config value — no edits to callers or a central
 * switch.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface ProviderImpl {
}
