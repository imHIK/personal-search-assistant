package io.personalassistant.ingestion.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scheduler must obey each knowledge's resolved cadence, not re-arm everything every tick:
 * arm only knowledges whose {@code nextSyncDueAt} has arrived, then roll that due time forward by
 * the resolved schedule — and never busy-loop (reschedule even when there was nothing to arm).
 */
class ForwardCursorSchedulerTest {

    private static final SourceType TYPE = SourceType.LOCAL_FS;

    private InMemoryKnowledgeRepository knowledge;
    private InMemoryCursorRepository cursors;
    private ForwardCursorScheduler scheduler;

    @BeforeEach
    void setUp() {
        knowledge = new InMemoryKnowledgeRepository();
        cursors = new InMemoryCursorRepository();
        // Connector default = NONE, global default = 1 day; tests that need precise timing use a
        // custom per-knowledge schedule so the assertion is exact.
        ScheduleResolver resolver =
                new ScheduleResolver(new SingleConnectorRegistry(new StubConnector(TYPE, List.of())), "1d", "");
        scheduler = new ForwardCursorScheduler(knowledge, cursors, resolver);
    }

    private Knowledge schedule(String id, String cron, String interval, boolean enabled) {
        Knowledge kn = TestData.knowledgeWithSchedule(id, TYPE,
                new Knowledge.ScheduleSettings(cron, interval, enabled));
        knowledge.save(kn);
        return kn;
    }

    private void forwardCursor(String knId, CursorStatus status) {
        cursors.insertIfAbsent(new Cursor("cur_" + knId + "_F", knId, "root", Map.of(),
                CursorDirection.FORWARD, CursorPosition.start(), status, null,
                Cursor.Retry.zero(), Cursor.Stats.zero(), new Cursor.Scope(TYPE)));
    }

    private CursorStatus statusOf(String knId) {
        return cursors.findByKnowledge(knId).get(0).status();
    }

    @Test
    void armsDueKnowledgeAndRollsDueTimeForward() {
        schedule("k1", null, "30m", true); // nextSyncDueAt null => due now
        forwardCursor("k1", CursorStatus.IDLE);

        Instant before = Instant.now();
        scheduler.tick();

        assertEquals(CursorStatus.AVAILABLE, statusOf("k1"), "an idle forward cursor is re-armed when due");
        Instant due = knowledge.findById("k1").orElseThrow().nextSyncDueAt();
        assertNotNull(due, "next-due is recorded so the cadence is enforced next tick");
        assertTrue(due.isAfter(before.plus(Duration.ofMinutes(29)))
                        && due.isBefore(before.plus(Duration.ofMinutes(31))),
                "next-due rolled forward by the resolved 30m interval, was: " + due);
    }

    @Test
    void skipsKnowledgeThatIsNotYetDue() {
        Knowledge kn = schedule("k1", null, "30m", true);
        Instant future = Instant.now().plus(Duration.ofHours(1));
        knowledge.updateNextSyncDueAt("k1", future);
        forwardCursor("k1", CursorStatus.IDLE);

        scheduler.tick();

        assertEquals(CursorStatus.IDLE, statusOf("k1"), "not due yet => not re-armed");
        assertEquals(future, knowledge.findById("k1").orElseThrow().nextSyncDueAt(),
                "an un-due knowledge's due time is left untouched");
    }

    @Test
    void skipsWhenSchedulingDisabled() {
        schedule("k1", null, null, false); // enabled=false
        forwardCursor("k1", CursorStatus.IDLE);

        scheduler.tick();

        assertEquals(CursorStatus.IDLE, statusOf("k1"), "scheduling off => never re-armed");
        assertNull(knowledge.findById("k1").orElseThrow().nextSyncDueAt(),
                "disabled scheduling leaves no due time");
    }

    @Test
    void reschedulesEvenWhenNothingWasArmed() {
        // Forward cursor still AVAILABLE (not yet caught up to IDLE): nothing to arm, but the due
        // time must still advance so the scheduler doesn't re-evaluate this knowledge every tick.
        schedule("k1", null, "30m", true);
        forwardCursor("k1", CursorStatus.AVAILABLE);

        scheduler.tick();

        assertEquals(CursorStatus.AVAILABLE, statusOf("k1"));
        assertNotNull(knowledge.findById("k1").orElseThrow().nextSyncDueAt(),
                "due time advances even when no cursor needed arming");
    }

    @Test
    void onlyArmsTheKnowledgeThatIsDue() {
        schedule("due", null, "30m", true); // due now
        schedule("later", null, "30m", true);
        knowledge.updateNextSyncDueAt("later", Instant.now().plus(Duration.ofHours(1)));
        forwardCursor("due", CursorStatus.IDLE);
        forwardCursor("later", CursorStatus.IDLE);

        scheduler.tick();

        assertEquals(CursorStatus.AVAILABLE, statusOf("due"));
        assertEquals(CursorStatus.IDLE, statusOf("later"), "a not-yet-due sibling is left alone");
    }
}
