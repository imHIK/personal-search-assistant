package io.personalassistant.digest.job;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.DigestService;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.testsupport.InMemoryDigestRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Due selection, and the guard that stops one broken digest becoming a hot loop. */
class DigestSchedulerTest {

    /** Records which digests were run, and can be made to throw. */
    private static final class RecordingDigestService implements DigestService {
        final List<String> ran = new ArrayList<>();
        RuntimeException failure;

        @Override
        public DigestRun run(String id) {
            ran.add(id);
            if (failure != null) {
                throw failure;
            }
            return new DigestRun("run_1", id, Instant.now(), List.of(), null, null);
        }

        @Override
        public Digest create(Digest digest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Digest> list() {
            return List.of();
        }

        @Override
        public Optional<Digest> get(String id) {
            return Optional.empty();
        }

        @Override
        public Digest setEnabled(String id, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public List<DigestRun> runs(String digestId, int limit) {
            return List.of();
        }

        @Override
        public Optional<DigestRun> latestRun(String digestId) {
            return Optional.empty();
        }
    }

    private final InMemoryDigestRepository repository = new InMemoryDigestRepository();
    private final RecordingDigestService service = new RecordingDigestService();

    private DigestScheduler scheduler() {
        StubConnector connector = new StubConnector(SourceType.LOCAL_FS,
                List.of(new SourceIterable("root", "root", Map.of())));
        ScheduleResolver resolver = new ScheduleResolver(new SingleConnectorRegistry(connector), "1d", null);
        DigestScheduler s = new DigestScheduler(repository, service, resolver);
        s.batch = 10;
        return s;
    }

    private void stored(String id, boolean enabled, Instant nextRunAt) {
        repository.save(new Digest(id, "d", "engineer", null, List.of(), Map.of(), "1d",
                SyncSchedule.ofInterval(Duration.ofDays(1)), null, 10, false, null, true, enabled,
                nextRunAt, Instant.now(), Instant.now()));
    }

    @Test
    void runsOnlyDigestsThatAreDue() {
        stored("dig_due", true, Instant.now().minus(Duration.ofMinutes(1)));
        stored("dig_later", true, Instant.now().plus(Duration.ofDays(1)));
        stored("dig_off", false, null);

        scheduler().tick();

        Assertions.assertEquals(List.of("dig_due"), service.ran);
    }

    @Test
    void aNeverRunDigestIsDue() {
        stored("dig_fresh", true, null);

        scheduler().tick();

        Assertions.assertEquals(List.of("dig_fresh"), service.ran);
    }

    @Test
    void advancesTheDueTimeSoItDoesNotRunAgainNextTick() {
        stored("dig_due", true, null);
        DigestScheduler scheduler = scheduler();

        scheduler.tick();
        scheduler.tick();

        Assertions.assertEquals(List.of("dig_due"), service.ran, "the second tick must find nothing due");
        Assertions.assertNotNull(repository.store.get("dig_due").nextRunAt());
    }

    @Test
    void aFailingDigestStillHasItsDueTimeAdvanced() {
        // Otherwise it stays due and is retried every tick — one broken digest becomes a hot loop
        // against the LLM.
        stored("dig_broken", true, null);
        service.failure = new IllegalStateException("boom");
        DigestScheduler scheduler = scheduler();

        scheduler.tick();
        scheduler.tick();

        Assertions.assertEquals(List.of("dig_broken"), service.ran);
    }

    @Test
    void aDigestWithNoCadenceOfItsOwnFallsBackToTheGlobalDefault() {
        // Leaving it permanently due would run it on every tick.
        repository.save(new Digest("dig_nocadence", "d", "engineer", null, List.of(), Map.of(), "1d",
                SyncSchedule.NONE, null, 10, false, null, true, true, null, Instant.now(), Instant.now()));

        scheduler().tick();

        Assertions.assertNotNull(repository.store.get("dig_nocadence").nextRunAt());
    }
}
