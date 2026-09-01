package io.personalassistant.app;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.testsupport.InMemoryDigestRepository;
import io.personalassistant.testsupport.StubSearchAgent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What makes a digest a digest: the look-back window, and only reporting what an earlier run did not.
 */
class DefaultDigestServiceTest {

    /** Returns a scripted result set and records the query it was given. */
    private static final class RecordingSearch implements SearchService {
        List<SearchHit> result = List.of();
        SearchQuery lastQuery;
        RuntimeException failure;

        @Override
        public SearchResponse search(SearchQuery query) {
            this.lastQuery = query;
            if (failure != null) {
                throw failure;
            }
            return new SearchResponse(result, null, null, 1);
        }
    }

    private final InMemoryDigestRepository repository = new InMemoryDigestRepository();
    private final RecordingSearch search = new RecordingSearch();

    private static SearchHit hit(String entityId) {
        return new SearchHit(entityId + "_0", entityId, "kn_1", 0, "Title " + entityId, "text",
                "snippet", "uri://" + entityId, 1.0, Map.of());
    }

    private DefaultDigestService service(StubSearchAgent agent) {
        DefaultDigestService svc = new DefaultDigestService(repository, search, agent);
        svc.newItemMultiplier = 4;
        return svc;
    }

    private Digest digest(String window, String taskId, boolean onlyNew, int topK) {
        return new Digest(null, "New postings", "engineer", null, List.of(), Map.of(), window,
                SyncSchedule.ofInterval(Duration.ofDays(1)), taskId, topK, false, null, onlyNew,
                true, null, null, null);
    }

    @Test
    void appliesTheLookBackWindowAsARangeFilterOnIndexedAt() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));

        svc.run(created.id());

        Object filter = search.lastQuery.filters().get(DefaultDigestService.INDEXED_AT);
        Assertions.assertInstanceOf(Map.class, filter, "a window must be a range, not a term");
        Assertions.assertTrue(((Map<?, ?>) filter).containsKey("gte"));
    }

    @Test
    void aDigestWithNoWindowAppliesNoTimeFilter() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest(null, null, false, 10));

        svc.run(created.id());

        Assertions.assertFalse(search.lastQuery.filters().containsKey(DefaultDigestService.INDEXED_AT));
    }

    @Test
    void reportsOnlyWhatEarlierRunsDidNot() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"), hit("ent_b"));
        svc.run(created.id());

        search.result = List.of(hit("ent_a"), hit("ent_b"), hit("ent_c"));
        DigestRun second = svc.run(created.id());

        Assertions.assertEquals(List.of("ent_c"),
                second.items().stream().map(DigestRun.Item::entityId).toList());
    }

    @Test
    void newnessIsKeyedOnTheEntityNotTheChunk() {
        // A chunk id changes whenever a document is re-chunked or re-indexed — which retention makes
        // routine — so a chunk-keyed check would re-report documents the user has already seen.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        svc.run(created.id());

        search.result = List.of(new SearchHit("ent_a_7", "ent_a", "kn_1", 7, "Title", "text",
                "snippet", "uri://a", 1.0, Map.of()));
        DigestRun second = svc.run(created.id());

        Assertions.assertTrue(second.items().isEmpty(),
                "a re-indexed document is not new, however its chunks were renumbered");
    }

    @Test
    void overFetchesSoFamiliarTopResultsDoNotHideNewOnes() {
        // Without this, a digest whose top 10 are all familiar reports nothing while new items sit
        // just below the cut.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 5));

        svc.run(created.id());

        Assertions.assertEquals(20, search.lastQuery.topK(), "5 requested x4 multiplier");
    }

    @Test
    void onlyNewOffReportsEverythingTrimmedToTopK() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, false, 2));
        search.result = List.of(hit("ent_a"), hit("ent_b"), hit("ent_c"));

        Assertions.assertEquals(2, svc.run(created.id()).items().size());
        Assertions.assertEquals(2, search.lastQuery.topK(), "no over-fetch is needed without filtering");
    }

    @Test
    void runsTheNamedTaskOverTheResults() {
        StubSearchAgent agent = new StubSearchAgent("{\"postings\": []}");
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertEquals(List.of("job-fit"), agent.taskIds);
        Assertions.assertEquals("{\"postings\": []}", run.taskOutput());
    }

    @Test
    void skipsTheTaskWhenThereIsNothingNewToScore() {
        StubSearchAgent agent = new StubSearchAgent("scored");
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));

        DigestRun run = svc.run(created.id());

        Assertions.assertTrue(agent.taskIds.isEmpty(), "an empty batch must not spend an LLM call");
        Assertions.assertNull(run.taskOutput());
    }

    @Test
    void aFailureIsRecordedAsARunRatherThanThrown() {
        // A scheduled job that throws leaves no trace a user ever sees; "broken for a week" must be
        // visible in the history.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.failure = new IllegalStateException("OpenSearch unreachable");

        DigestRun run = svc.run(created.id());

        Assertions.assertEquals("OpenSearch unreachable", run.error());
        Assertions.assertTrue(run.items().isEmpty());
        Assertions.assertEquals(1, repository.findRuns(created.id(), 10).size());
    }

    @Test
    void aFailedRunDoesNotPoisonTheAlreadySeenSet() {
        // A failed run records no items, so nothing it "found" can be suppressed next time.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.failure = new IllegalStateException("down");
        svc.run(created.id());

        search.failure = null;
        search.result = List.of(hit("ent_a"));
        DigestRun recovered = svc.run(created.id());

        Assertions.assertEquals(List.of("ent_a"),
                recovered.items().stream().map(DigestRun.Item::entityId).toList());
    }

    @Test
    void aNewDigestIsDueImmediatelyRatherThanAfterOneWholeInterval() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));

        Assertions.assertNull(created.nextRunAt());
        Assertions.assertEquals(List.of(created.id()),
                new ArrayList<>(repository.findDue(Instant.now(), 10)).stream().map(Digest::id).toList());
    }

    @Test
    void aDisabledDigestIsNeverDueButStillRunsByHand() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        svc.setEnabled(created.id(), false);

        Assertions.assertTrue(repository.findDue(Instant.now(), 10).isEmpty());
        Assertions.assertNotNull(svc.run(created.id()), "an explicit run ignores the switch");
    }

    @Test
    void deletingADigestTakesItsRunsWithIt() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        svc.run(created.id());

        svc.delete(created.id());

        Assertions.assertTrue(repository.findRuns(created.id(), 10).isEmpty());
    }
}
