package io.personalassistant.ingestion.schedule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleResolverTest {

    private static final SourceType TYPE = SourceType.LOCAL_FS;

    private ScheduleResolver resolverWith(SyncSchedule connectorDefault, String globalInterval, String globalCron) {
        StubConnector connector = new StubConnector(TYPE, List.of()).withDefaultSchedule(connectorDefault);
        return new ScheduleResolver(new SingleConnectorRegistry(connector), globalInterval, globalCron);
    }

    private Knowledge withSchedule(String cron, String interval, boolean enabled) {
        return TestData.knowledgeWithSchedule("k1", TYPE,
                new Knowledge.ScheduleSettings(cron, interval, enabled));
    }

    @Test
    void customIntervalBeatsConnectorAndGlobal() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.ofInterval(Duration.ofHours(6)), "1d", "");
        SyncSchedule resolved = resolver.resolve(withSchedule(null, "15m", true));
        assertEquals(Duration.ofMinutes(15), resolved.interval());
        assertFalse(resolved.usesCron());
    }

    @Test
    void customCronBeatsItsOwnIntervalAndLowerTiers() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.ofInterval(Duration.ofHours(6)), "1d", "");
        SyncSchedule resolved = resolver.resolve(withSchedule("0 0 2 * * ?", "15m", true));
        assertTrue(resolved.usesCron(), "cron is the more specific instruction, so it wins");
        assertEquals("0 0 2 * * ?", resolved.cron());
    }

    @Test
    void connectorDefaultUsedWhenNoCustom() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.ofInterval(Duration.ofHours(6)), "1d", "");
        SyncSchedule resolved = resolver.resolve(withSchedule(null, null, true));
        assertEquals(Duration.ofHours(6), resolved.interval(), "falls through to the connector tier");
    }

    @Test
    void globalDefaultUsedWhenNoCustomAndConnectorHasNone() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        SyncSchedule resolved = resolver.resolve(withSchedule(null, null, true));
        assertEquals(Duration.ofDays(1), resolved.interval(), "falls all the way through to global");
    }

    @Test
    void defaultConfigKnowledgeInheritsConnectorDefault() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.ofInterval(Duration.ofDays(1)), "7d", "");
        Knowledge defaulted = TestData.knowledge("k1", TYPE, Instant.now(), java.util.Map.of());
        assertEquals(Duration.ofDays(1), resolver.resolve(defaulted).interval());
    }

    @Test
    void nextDueForIntervalIsFromPlusInterval() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        Instant from = Instant.parse("2026-06-28T10:15:30Z");
        assertEquals(from.plus(Duration.ofMinutes(15)),
                resolver.nextDueAt(SyncSchedule.ofInterval(Duration.ofMinutes(15)), from));
    }

    @Test
    void nextDueForCronIsNextMatchingWallClock() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        Instant from = Instant.parse("2026-06-28T10:15:30Z");
        // "second 0, minute 0, every hour" => next top of the hour in UTC.
        assertEquals(Instant.parse("2026-06-28T11:00:00Z"),
                resolver.nextDueAt(SyncSchedule.ofCron("0 0 * * * ?"), from));
    }

    @Test
    void nextDueForEmptyScheduleFallsBackToGlobal() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        Instant from = Instant.parse("2026-06-28T10:15:30Z");
        assertEquals(from.plus(Duration.ofDays(1)), resolver.nextDueAt(SyncSchedule.NONE, from));
    }

    @Test
    void globalCronWinsOverGlobalIntervalWhenBothConfigured() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "0 0 * * * ?");
        assertTrue(resolver.globalDefault().usesCron());
    }

    @Test
    void fiveFieldUnixCronIsAccepted() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        Instant from = Instant.parse("2026-06-28T10:15:30Z");
        assertEquals(Instant.parse("2026-06-28T18:00:00Z"),
                resolver.nextDueAt(SyncSchedule.ofCron("0 9,18 * * *"), from));
    }

    @Test
    void weekdaysMeanTheSameDayInBothDialects() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "1d", "");
        Instant sunday = Instant.parse("2026-06-28T10:15:30Z");
        Instant monday9 = Instant.parse("2026-06-29T09:00:00Z");
        // Monday is 1 in Unix cron and 2 in Quartz.
        assertEquals(monday9, resolver.nextDueAt(SyncSchedule.ofCron("0 9 * * 1"), sunday));
        assertEquals(monday9, resolver.nextDueAt(SyncSchedule.ofCron("0 0 9 ? * 2"), sunday));
    }

    @Test
    void requireValidCronAcceptsBothDialectsAndNoCron() {
        assertDoesNotThrow(() -> ScheduleResolver.requireValidCron("0 9,18 * * *"));
        assertDoesNotThrow(() -> ScheduleResolver.requireValidCron("0 0 9,18 * * ?"));
        assertDoesNotThrow(() -> ScheduleResolver.requireValidCron(null));
        assertDoesNotThrow(() -> ScheduleResolver.requireValidCron("  "));
    }

    @Test
    void requireValidCronRejectsGarbageWithTheAcceptedForms() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleResolver.requireValidCron("every morning"));
        assertTrue(e.getMessage().contains("every morning") && e.getMessage().contains("5 fields"),
                "the message names the bad expression and the forms that work: " + e.getMessage());
    }

    @Test
    void unparseableStoredCronFallsBackToGlobalIntervalInsteadOfThrowing() {
        ScheduleResolver resolver = resolverWith(SyncSchedule.NONE, "6h", "");
        Instant from = Instant.parse("2026-06-28T10:15:30Z");
        assertEquals(from.plus(Duration.ofHours(6)),
                resolver.nextDueAt(SyncSchedule.ofCron("every morning"), from));
    }
}
