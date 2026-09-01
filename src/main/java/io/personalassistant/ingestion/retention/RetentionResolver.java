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
 * Resolves <em>how long</em> a knowledge's entities are kept, following the same three-tier
 * precedence as {@link io.personalassistant.ingestion.schedule.ScheduleResolver}:
 *
 * <ol>
 *   <li><b>Custom</b> — {@link Knowledge.Retention} on the knowledge.</li>
 *   <li><b>Connector default</b> — {@link io.personalassistant.ingestion.connector.SourceConnector#defaultRetention()}
 *       (feed-like sources only; document sources have no opinion).</li>
 *   <li><b>Global default</b> — {@code app.retention.default-period}, normally unset.</li>
 * </ol>
 *
 * <p><strong>Unset at every tier means never expire</strong>, and that is the shipped default. This is
 * the safety property of the whole feature: retention is opt-in, so a Drive or mail corpus cannot
 * silently delete itself because a connector stopped being walked. Only a knowledge (or connector)
 * that positively asks for a window gets one.
 *
 * <p>Age is measured from {@code Entity.createdAt}, not {@code updatedAt} — an item that has sat
 * unchanged is exactly the case retention is for, so a change-based clock would never fire.
 */
@ApplicationScoped
public class RetentionResolver {

    private final ConnectorRegistry connectors;

    /** Optional: blank/unset means "no global retention", i.e. entities never expire. See {@link ConfigText}. */
    @ConfigProperty(name = "app.retention.default-period")
    Optional<String> defaultPeriod;

    @Inject
    public RetentionResolver(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    /** Test-friendly constructor that sets the global-default tier explicitly (CDI uses the other). */
    public RetentionResolver(ConnectorRegistry connectors, String defaultPeriod) {
        this.connectors = connectors;
        this.defaultPeriod = Optional.ofNullable(defaultPeriod);
    }

    /**
     * The effective retention window for a knowledge, or {@code null} when it never expires.
     *
     * <p>A knowledge whose connector is not registered resolves to the global tier rather than
     * throwing: the sweeper runs over every knowledge, and one unknown connector type must not stop
     * the rest from being swept.
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

    /** The global-default tier, read from config. {@code null} when unset — the shipped state. */
    public Duration globalDefault() {
        return Durations.parse(ConfigText.orNull(defaultPeriod));
    }

    /**
     * The {@code createdAt} boundary for a knowledge at {@code now}: entities created strictly before
     * this are past their window. Returns {@code null} when the knowledge never expires, which callers
     * must read as "sweep nothing" rather than "sweep everything".
     */
    public Instant cutoffFor(Knowledge knowledge, Instant now) {
        Duration window = resolve(knowledge);
        return window == null ? null : now.minus(window);
    }
}
