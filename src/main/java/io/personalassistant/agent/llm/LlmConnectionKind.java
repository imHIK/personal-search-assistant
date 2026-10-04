package io.personalassistant.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.connection.ConnectionKind;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verification lists the endpoint's models rather than completing a prompt: the health sweep re-runs it
 * every interval, and a listing costs no tokens.
 */
@ApplicationScoped
public class LlmConnectionKind implements ConnectionKind {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final OutboundHttp http;
    private final ConnectionRepository connections;

    @Inject
    public LlmConnectionKind(OutboundHttp http, ConnectionRepository connections) {
        this.http = http;
        this.connections = connections;
    }

    @Override
    public String id() {
        return LlmConnections.TYPE;
    }

    /** @throws IllegalArgumentException with a reason fit to show a user */
    @Override
    public void verify(Connection connection) {
        String baseUrl = LlmConnections.baseUrl(connection)
                .orElseThrow(() -> new IllegalArgumentException("baseUrl is required"));
        String model = LlmConnections.model(connection)
                .orElseThrow(() -> new IllegalArgumentException("model is required"));
        LlmConnections.temperature(connection);
        LlmConnections.maxTokens(connection);
        LlmConnections.profile(connection).ifPresent(profile -> {
            for (Connection other : connections.findByType(LlmConnections.TYPE)) {
                if (!Objects.equals(other.id(), connection.id())
                        && LlmConnections.profile(other).filter(profile::equals).isPresent()) {
                    throw new IllegalArgumentException("The connection \"" + other.name()
                            + "\" already serves the profile " + profile);
                }
            }
        });

        String key = LlmConnections.apiKey(connection).orElse(null);
        HttpCall call = HttpCall.get(baseUrl.replaceAll("/+$", "") + "/models", TIMEOUT, RateLimit.NONE)
                .acceptJson()
                .header("Authorization", key == null ? null : "Bearer " + key);
        JsonNode listing;
        try {
            listing = http.json(call);
        } catch (OutboundHttpException e) {
            if (e.status() == 401 || e.status() == 403) {
                throw new IllegalArgumentException("The endpoint rejected the API key (HTTP " + e.status() + ")");
            }
            throw new IllegalArgumentException("Could not list models at " + baseUrl + ": "
                    + (e.status() > 0 ? "HTTP " + e.status() + " " + e.bodySnippet() : e.getMessage()));
        }

        List<String> offered = new ArrayList<>();
        for (JsonNode entry : listing.path("data")) {
            offered.add(entry.path("id").asText(""));
        }
        // Gemini lists "models/<name>" while requests take the bare name.
        boolean known = offered.stream().anyMatch(id -> id.equals(model) || id.endsWith("/" + model));
        if (!offered.isEmpty() && !known) {
            throw new IllegalArgumentException("The endpoint does not offer the model " + model);
        }
    }
}
