package io.personalassistant.ingestion.retention;

import io.personalassistant.common.ConfigText;
import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Custom, then the connector default, then {@code app.retention.default-period}. Unset everywhere means never
 * expire, so a corpus cannot delete itself. Age runs from createdAt: an item sitting unchanged is exactly
 * what retention is for.
 */
@ApplicationScoped
public class RetentionResolver {

    private final ConnectorRegistry connectors;

    /** Blank means no global retention. */
    @ConfigProperty(name = "app.retention.default-period")
    Optional<String> defaultPeriod;

    @Inject
    public RetentionResolver(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    public RetentionResolver(ConnectorRegistry connectors, String defaultPeriod) {
        this.connectors = connectors;
        this.defaultPeriod = Optional.ofNullable(defaultPeriod);
    }

    /**
     * Null when it never expires. An unregistered connector falls to the global tier rather than stopping the
     * sweep.
     */
    public Duration resolve(Knowledge knowledge) {
        Duration custom = knowledge.config() != null && knowledge.config().retention() != null
                ? knowledge.config().retention().custom()
                : null;
        if (custom != null) {
            return custom;
        }
        Optional<Duration> connectorDefault = connectorDefault(knowledge);
        if (connectorDefault.isPresent()) {
            return connectorDefault.get();
        }
        return globalDefault();
    }

    private Optional<Duration> connectorDefault(Knowledge knowledge) {
        if (knowledge.connectorDetails() == null || knowledge.connectorDetails().type() == null
                || !connectors.supports(knowledge.connectorDetails().type())) {
            return Optional.empty();
        }
        return connectors.get(knowledge.connectorDetails().type()).defaultRetention();
    }

    public Duration globalDefault() {
        return Durations.parse(ConfigText.orNull(defaultPeriod));
    }

    /** Null means sweep nothing, never everything. */
    public Instant cutoffFor(Knowledge knowledge, Instant now) {
        Duration window = resolve(knowledge);
        return window == null ? null : now.minus(window);
    }
}
