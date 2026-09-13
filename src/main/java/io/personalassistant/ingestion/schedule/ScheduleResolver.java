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
 * "next fire" is computed with cron-utils, always in UTC. Two dialects are accepted, told apart by
 * field count: a 5-field Unix cron ({@code "0 2 * * *"}), which is what people type and what the
 * console asks for, and a 6/7-field Quartz cron ({@code "0 0 2 * * ?"}), which is what the app used
 * first. Each is parsed by its own cron-utils definition rather than rewritten into the other, because
 * the two number weekdays differently (Unix Sunday is 0, Quartz Sunday is 1) and Quartz insists on a
 * {@code ?} in one day field — a string rewrite gets both wrong in ways that still parse.
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

    /** Optional: blank/unset means "no global cron", so interval wins. See {@link ConfigText}. */
    @ConfigProperty(name = "app.scheduler.default-cron")
    Optional<String> defaultCron;

    @Inject
    public ScheduleResolver(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    /** Test-friendly constructor that sets the global-default config explicitly (CDI uses the other). */
    public ScheduleResolver(ConnectorRegistry connectors, String defaultInterval, String defaultCron) {
        this.connectors = connectors;
        this.defaultInterval = defaultInterval;
        this.defaultCron = Optional.ofNullable(defaultCron);
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
        String cron = ConfigText.orNull(defaultCron);
        if (cron != null) {
            return SyncSchedule.ofCron(cron);
        }
        return SyncSchedule.ofInterval(defaultIntervalOrDay());
    }

    /** Convenience: resolve the schedule and compute the next due time for a knowledge from {@code from}. */
    public Instant nextDueAt(Knowledge knowledge, Instant from) {
        return nextDueAt(resolve(knowledge), from);
    }

    /**
     * The next instant at or after {@code from} that the given schedule fires. For a cron this is the
     * next matching wall-clock time (UTC); for an interval it is simply {@code from + interval}. An
     * empty schedule defensively falls back to the global default so a due time is always produced.
     *
     * <p>An unparseable cron falls back to the global <em>interval</em> (never the global cron, which
     * could be the broken one) instead of throwing. Writes are validated through
     * {@link #requireValidCron}, but a cron stored before that check existed still reaches here, and
     * throwing would leave the record due forever — retried, and failing, on every scheduler tick.
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
                    // A cron with no future match (rare; e.g. impossible date) shouldn't wedge the
                    // scheduler — re-check a day later.
                    .orElse(from.plus(Duration.ofDays(1)));
        }
        return from.plus(schedule.interval());
    }

    /**
     * Reject a cron the scheduler could not run, so a bad expression is a {@code 400} at save time rather
     * than a warning in the log on every tick. {@code null} or blank passes — it means "no cron".
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

    /** Pick the dialect by field count: 5 is Unix, anything else is handed to Quartz (6 or 7). */
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
