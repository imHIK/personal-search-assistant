package io.personalassistant.ingestion.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Custom, then the connector default, then the global default; cron wins over interval within a tier. Cron
 * runs in UTC, in two dialects told apart by field count, 5-field Unix and 6/7-field Quartz, each parsed by
 * its own definition since they number weekdays differently.
 */
@ApplicationScoped
public class ScheduleResolver {

    private static final Logger LOG = Logger.getLogger(ScheduleResolver.class.getName());

    private static final CronParser QUARTZ =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    private static final CronParser UNIX =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private final ConnectorRegistry connectors;

    @ConfigProperty(name = "app.scheduler.default-interval", defaultValue = "1d")
    String defaultInterval;

    /** Blank means no global cron, so the interval wins. */
    @ConfigProperty(name = "app.scheduler.default-cron")
    Optional<String> defaultCron;

    @Inject
    public ScheduleResolver(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    public ScheduleResolver(ConnectorRegistry connectors, String defaultInterval, String defaultCron) {
        this.connectors = connectors;
        this.defaultInterval = defaultInterval;
        this.defaultCron = Optional.ofNullable(defaultCron);
    }

    public SyncSchedule resolve(Knowledge knowledge) {
        SyncSchedule custom = knowledge.config() != null && knowledge.config().scheduleSettings() != null
                ? knowledge.config().scheduleSettings().customSchedule()
                : SyncSchedule.NONE;
        if (custom.isPresent()) {
            return custom;
        }
        SyncSchedule connectorDefault = connectors.get(knowledge.connectorDetails().type()).defaultSchedule();
        if (connectorDefault != null && connectorDefault.isPresent()) {
            return connectorDefault;
        }
        return globalDefault();
    }

    public SyncSchedule globalDefault() {
        String cron = ConfigText.orNull(defaultCron);
        if (cron != null) {
            return SyncSchedule.ofCron(cron);
        }
        return SyncSchedule.ofInterval(defaultIntervalOrDay());
    }

    public Instant nextDueAt(Knowledge knowledge, Instant from) {
        return nextDueAt(resolve(knowledge), from);
    }

    /**
     * An empty schedule falls back to the global default. An unparseable cron falls back to the global
     * interval rather than throwing, which would leave the record due forever.
     */
    public Instant nextDueAt(SyncSchedule schedule, Instant from) {
        if (schedule == null || !schedule.isPresent()) {
            return nextDueAt(globalDefault(), from);
        }
        if (schedule.usesCron()) {
            Cron cron;
            try {
                cron = parse(schedule.cron());
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, "Unparseable cron '" + schedule.cron()
                        + "'; falling back to the global default interval", e);
                return from.plus(defaultIntervalOrDay());
            }
            ZonedDateTime base = ZonedDateTime.ofInstant(from, ZoneOffset.UTC);
            return ExecutionTime.forCron(cron)
                    .nextExecution(base)
                    .map(ZonedDateTime::toInstant)
                    // A cron with no future match must not wedge the scheduler: re-check a day later.
                    .orElse(from.plus(Duration.ofDays(1)));
        }
        return from.plus(schedule.interval());
    }

    /**
     * Null or blank passes.
     *
     * @throws IllegalArgumentException naming the expression and both accepted forms
     */
    public static void requireValidCron(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            parse(expression).validate();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron '" + expression + "': " + e.getMessage()
                    + ". Use 5 fields (\"0 9 * * *\") or Quartz 6 fields (\"0 0 9 * * ?\"); times are UTC", e);
        }
    }

    /** 5 fields is Unix; anything else goes to Quartz. */
    private static Cron parse(String expression) {
        String trimmed = expression.trim();
        CronParser parser = trimmed.split("\\s+").length == 5 ? UNIX : QUARTZ;
        return parser.parse(trimmed);
    }

    private Duration defaultIntervalOrDay() {
        Duration interval = Durations.parse(defaultInterval);
        return interval == null ? Duration.ofDays(1) : interval;
    }
}
