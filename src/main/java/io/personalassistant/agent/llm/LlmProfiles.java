package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;

/**
 * Resolves profiles from {@code app.llm.profile.<name>.<key>} dynamically, so a new profile is only new
 * properties. Blank values count as absent (SmallRye turns empty into null). An unknown name inherits
 * everything with a warning rather than failing the call.
 */
@ApplicationScoped
public class LlmProfiles {

    private static final Logger LOG = Logger.getLogger(LlmProfiles.class.getName());

    private static final String PREFIX = "app.llm.profile.";

    private final Config config;

    private final ConcurrentHashMap<String, LlmProfile> cache = new ConcurrentHashMap<>();

    @Inject
    public LlmProfiles(Config config) {
        this.config = config;
    }

    public LlmProfile get(String name) {
        if (name == null || name.isBlank()) {
            return LlmProfile.inherit("default");
        }
        return cache.computeIfAbsent(name, this::resolve);
    }

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

    private static RateLimitMode mode(String value) {
        return RateLimitMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }

    private Optional<String> text(String profile, String key) {
        return config.getOptionalValue(PREFIX + profile + "." + key, String.class)
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }
}
