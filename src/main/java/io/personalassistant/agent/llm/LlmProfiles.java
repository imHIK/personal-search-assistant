package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;

/**
 * An ACTIVE LLM connection serving the profile wins, then the default LLM connection, then
 * {@code app.llm.profile.<name>.<key>}. Connections are read on every call, so an edit applies to the next
 * one; only the config side is cached. Blank values count as absent (SmallRye turns empty into null). An
 * unknown name inherits everything with a warning rather than failing the call.
 */
@ApplicationScoped
public class LlmProfiles {

    private static final Logger LOG = Logger.getLogger(LlmProfiles.class.getName());

    private static final String PREFIX = "app.llm.profile.";

    private static final String DEFAULT = "default";

    private final Config config;
    private final ConnectionRepository connections;

    private final ConcurrentHashMap<String, LlmProfile> cache = new ConcurrentHashMap<>();

    @Inject
    public LlmProfiles(Config config, ConnectionRepository connections) {
        this.config = config;
        this.connections = connections;
    }

    public LlmProfile get(String name) {
        boolean unnamed = name == null || name.isBlank();
        LlmProfile configured = unnamed
                ? LlmProfile.inherit(DEFAULT)
                : cache.computeIfAbsent(name, this::resolve);
        return connectionFor(unnamed ? DEFAULT : name)
                .map(c -> fromConnection(configured, c))
                .orElse(configured);
    }

    public List<String> names() {
        Set<String> out = new LinkedHashSet<>();
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
        for (Connection c : connections.findByType(LlmConnections.TYPE)) {
            LlmConnections.profile(c).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    private Optional<Connection> connectionFor(String name) {
        List<Connection> active = connections.findByType(LlmConnections.TYPE).stream()
                .filter(c -> c.status() == ConnectionStatus.ACTIVE)
                .toList();
        return active.stream()
                .filter(c -> LlmConnections.profile(c).filter(name::equals).isPresent())
                .findFirst()
                .or(() -> active.stream().filter(Connection::isDefault).findFirst());
    }

    /**
     * Endpoint, key and model always come from the connection together: mixing a connection's endpoint with
     * a configured key would send one vendor's secret to another's host. Temperature and max-tokens fall back
     * to the configured profile, so a connection need not repeat them.
     */
    private static LlmProfile fromConnection(LlmProfile configured, Connection c) {
        return new LlmProfile(configured.name(),
                LlmConnections.baseUrl(c),
                LlmConnections.model(c),
                LlmConnections.temperature(c).or(configured::temperature),
                LlmConnections.maxTokens(c).or(configured::maxTokens),
                LlmConnections.apiKey(c),
                configured.rateLimitMode(),
                Optional.of(c.id()),
                Optional.ofNullable(c.rateLimit()));
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
