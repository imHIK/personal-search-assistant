package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;

/**
 * Resolves {@link LlmProfile}s from {@code app.llm.profile.<name>.<key>} configuration.
 *
 * <p>Deliberately <em>not</em> a fixed set of {@code @ConfigProperty} fields. Keys are looked up
 * dynamically through {@link Config}, so a new profile is created by adding properties — no new bean,
 * no new injection point, no code change. That is the whole point: the roadmap adds an LLM call for
 * reranking, one for query rephrasing and one for index-time enrichment, and each of those wants its
 * own model without dragging a config refactor along with it.
 *
 * <p>Recognised sub-keys, all optional: {@code base-url}, {@code model}, {@code temperature},
 * {@code max-tokens}, {@code api-key}, and {@code rate-limit-mode} ({@code wait} or {@code fail-fast}).
 * An absent or blank value means "inherit the provider default" (see {@link LlmProfile}), which is also
 * why blank is treated as absent — SmallRye converts an empty property to null, and a
 * {@code ${ENV_VAR:}}-backed key is empty exactly when the variable is unset.
 *
 * <p>An unknown profile name resolves to {@link LlmProfile#inherit} with a warning rather than
 * throwing. A missing profile means the call runs on the provider's configured model — degraded, but
 * working — whereas failing hard would take out answering because a reranker's profile was misspelled.
 */
@ApplicationScoped
public class LlmProfiles {

    private static final Logger LOG = Logger.getLogger(LlmProfiles.class.getName());

    private static final String PREFIX = "app.llm.profile.";

    private final Config config;

    /** Resolved profiles are immutable and re-read per call otherwise; cache to keep lookups cheap. */
    private final ConcurrentHashMap<String, LlmProfile> cache = new ConcurrentHashMap<>();

    @Inject
    public LlmProfiles(Config config) {
        this.config = config;
    }

    /**
     * The profile configured under {@code name}. Never null: a name with no properties at all yields an
     * inherit-everything profile, so a caller can always name the role it is playing.
     */
    public LlmProfile get(String name) {
        if (name == null || name.isBlank()) {
            return LlmProfile.inherit("default");
        }
        return cache.computeIfAbsent(name, this::resolve);
    }

    /**
     * Every profile name the configuration defines, in encounter order.
     *
     * <p>Derived by scanning property names rather than kept as a list, for the same reason
     * {@link #get} looks keys up dynamically: a profile is created by adding properties, so any
     * declared set would be a second place to remember. This exists so a user choosing which model
     * runs their task can be offered the real options instead of typing a name.
     */
    public java.util.List<String> names() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String property : config.getPropertyNames()) {
            if (!property.startsWith(PREFIX)) {
                continue;
            }
            String rest = property.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                out.add(rest.substring(0, dot));
            }
        }
        return java.util.List.copyOf(out);
    }

    private LlmProfile resolve(String name) {
        Optional<String> baseUrl = text(name, "base-url");
        Optional<String> model = text(name, "model");
        Optional<Double> temperature = text(name, "temperature").map(Double::valueOf);
        Optional<Integer> maxTokens = text(name, "max-tokens").map(Integer::valueOf);
        Optional<String> apiKey = text(name, "api-key");
        Optional<RateLimitMode> rateLimitMode = text(name, "rate-limit-mode").map(LlmProfiles::mode);

        if (baseUrl.isEmpty() && model.isEmpty() && temperature.isEmpty()
                && maxTokens.isEmpty() && apiKey.isEmpty() && rateLimitMode.isEmpty()) {
            LOG.warning("No configuration found for LLM profile \"" + name + "\" (expected "
                    + PREFIX + name + ".model or similar); falling back to the provider defaults");
            return LlmProfile.inherit(name);
        }
        LOG.fine(() -> "LLM profile \"" + name + "\": model=" + model.orElse("<provider default>")
                + ", temperature=" + temperature.map(String::valueOf).orElse("<provider default>")
                + ", max-tokens=" + maxTokens.map(String::valueOf).orElse("<unset>")
                + ", endpoint=" + (baseUrl.isPresent() ? baseUrl.get() : "<provider default>"));
        return new LlmProfile(name, baseUrl, model, temperature, maxTokens, apiKey, rateLimitMode);
    }

    /** Accepts the kebab-case spelling used everywhere else in {@code application.properties}. */
    private static RateLimitMode mode(String value) {
        return RateLimitMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }

    /** Blank is treated as absent — see the class Javadoc on empty values and {@code ${ENV_VAR:}}. */
    private Optional<String> text(String profile, String key) {
        return config.getOptionalValue(PREFIX + profile + "." + key, String.class)
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }
}
