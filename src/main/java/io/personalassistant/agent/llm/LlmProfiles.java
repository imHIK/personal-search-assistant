package io.personalassistant.agent.llm;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a profile name to an LLM connection: the one serving that profile, else the default. Read on every
 * call, so an edit in the console applies to the next one. There is no configuration fallback.
 */
@ApplicationScoped
public class LlmProfiles {

    private static final String DEFAULT = "default";

    private final ConnectionRepository connections;
    private final PromptCatalog catalog;

    @Inject
    public LlmProfiles(ConnectionRepository connections, PromptCatalog catalog) {
        this.connections = connections;
        this.catalog = catalog;
    }

    /** @throws IllegalStateException when no usable LLM connection exists, naming where to add one */
    public LlmProfile get(String name) {
        String wanted = name == null || name.isBlank() ? DEFAULT : name;
        Connection c = connectionFor(wanted).orElseThrow(() -> new IllegalStateException(
                "No LLM connection serves the profile \"" + wanted + "\" and there is no default one; "
                        + "add one under Accounts → LLM"));
        return new LlmProfile(wanted, c.id(),
                LlmConnections.baseUrl(c).orElseThrow(() -> incomplete(c, LlmConnections.BASE_URL)),
                LlmConnections.model(c).orElseThrow(() -> incomplete(c, LlmConnections.MODEL)),
                LlmConnections.temperature(c),
                LlmConnections.maxTokens(c),
                LlmConnections.apiKey(c),
                c.rateLimit(),
                Optional.empty());
    }

    /** The profiles connections serve, plus those the bundled tasks ask for, so a picker can offer both. */
    public List<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (Connection c : connections.findByType(LlmConnections.TYPE)) {
            LlmConnections.profile(c).ifPresent(out::add);
        }
        for (TaskSpec task : catalog.tasks()) {
            out.add(task.llmProfile());
        }
        return List.copyOf(out);
    }

    /**
     * A connection in ERROR is still used: with no fallback, skipping it would turn a failed health check —
     * possibly transient — into a certain failure. Only DISABLED, an operator decision, takes it out.
     */
    private Optional<Connection> connectionFor(String name) {
        List<Connection> usable = connections.findByType(LlmConnections.TYPE).stream()
                .filter(c -> c.status() != ConnectionStatus.DISABLED)
                .toList();
        return usable.stream()
                .filter(c -> LlmConnections.profile(c).filter(name::equals).isPresent())
                .findFirst()
                .or(() -> usable.stream().filter(Connection::isDefault).findFirst());
    }

    private static IllegalStateException incomplete(Connection c, String field) {
        return new IllegalStateException("The LLM connection \"" + c.name() + "\" has no " + field);
    }
}
