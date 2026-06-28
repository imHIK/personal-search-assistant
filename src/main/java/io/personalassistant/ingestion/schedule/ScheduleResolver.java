package io.personalassistant.ingestion.schedule;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves <em>which</em> forward-sync schedule governs a knowledge, and computes the next time it
 * is due, following the three-tier precedence:
 *
 * <ol>
 *   <li><b>Custom</b> — the user's own {@link Knowledge.ScheduleSettings} on the knowledge.</li>
 *   <li><b>Connector default</b> — {@link io.personalassistant.ingestion.connector.SourceConnector#defaultSchedule()}
 *       (e.g. {@code LOCAL_FS} = 1 day); used when the user set no custom schedule.</li>
 *   <li><b>Global default</b> — {@code app.scheduler.default-interval} / {@code default-cron};
 *       the final fallback when neither of the above is present.</li>
 * </ol>
 *
 * <p>At each tier a schedule may be expressed as an <em>interval</em> or a <em>cron</em>; when both
 * are present at the winning tier, cron is preferred (it is the more specific instruction). Cron
 * "next fire" is computed with cron-utils against the Quartz dialect — matching the 6-field
 * expressions the rest of the app uses (e.g. {@code "0 0 2 * * ?"}).
 */
@ApplicationScoped
public class ScheduleResolver {

    private static final CronParser QUARTZ =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private final ConnectorRegistry connectors;

    @ConfigProperty(name = "app.scheduler.default-interval", defaultValue = "1d")
    String defaultInterval;

    @ConfigProperty(name = "app.scheduler.default-cron", defaultValue = "")
    String defaultCron;

    @Inject
    public ScheduleResolver(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    /** Test-friendly constructor that sets the global-default config explicitly (CDI uses the other). */
    public ScheduleResolver(ConnectorRegistry connectors, String defaultInterval, String defaultCron) {
        this.connectors = connectors;
        this.defaultInterval = defaultInterval;
        this.defaultCron = defaultCron;
    }

    /** The effective schedule for a knowledge, applying custom &rarr; connector &rarr; global. */
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

    /** The global-default tier, read from config. Cron wins over interval if both are configured. */
    public SyncSchedule globalDefault() {
        if (defaultCron != null && !defaultCron.isBlank()) {
            return SyncSchedule.ofCron(defaultCron);
        }
        Duration interval = Durations.parse(defaultInterval);
        return SyncSchedule.ofInterval(interval == null ? Duration.ofDays(1) : interval);
    }

    /** Convenience: resolve the schedule and compute the next due time for a knowledge from {@code from}. */
    public Instant nextDueAt(Knowledge knowledge, Instant from) {
        return nextDueAt(resolve(knowledge), from);
    }

    /**
     * The next instant at or after {@code from} that the given schedule fires. For a cron this is the
     * next matching wall-clock time (UTC); for an interval it is simply {@code from + interval}. An
     * empty schedule defensively falls back to the global default so a due time is always produced.
     */
    public Instant nextDueAt(SyncSchedule schedule, Instant from) {
        if (schedule == null || !schedule.isPresent()) {
            return nextDueAt(globalDefault(), from);
        }
        if (schedule.usesCron()) {
            ZonedDateTime base = ZonedDateTime.ofInstant(from, ZoneOffset.UTC);
            return ExecutionTime.forCron(QUARTZ.parse(schedule.cron()))
                    .nextExecution(base)
                    .map(ZonedDateTime::toInstant)
                    // A cron with no future match (rare; e.g. impossible date) shouldn't wedge the
                    // scheduler — re-check a day later.
                    .orElse(from.plus(Duration.ofDays(1)));
        }
        return from.plus(schedule.interval());
    }
}
