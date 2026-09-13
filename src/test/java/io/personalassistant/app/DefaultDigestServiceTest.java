package io.personalassistant.app;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.DigestPatch;
import io.personalassistant.domain.service.Patched;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.testsupport.InMemoryChannelRepository;
import io.personalassistant.testsupport.InMemoryDeliveryRepository;
import io.personalassistant.testsupport.InMemoryDigestRepository;
import io.personalassistant.testsupport.InMemoryTaskRepository;
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

    /**
     * Returns a scripted result set and records every query it was given.
     *
     * <p>Every query, not just the last: a run that comes back empty searches a second time with the
     * window removed, to work out whether the window is what emptied it. {@code queries.get(0)} is
     * always the digest's own search.
     */
    private static final class RecordingSearch implements SearchService {
        List<SearchHit> result = List.of();
        /** What an unwindowed search returns, when a test wants the two to differ. Null = same. */
        List<SearchHit> withoutWindow;
        final List<SearchQuery> queries = new ArrayList<>();
        RuntimeException failure;

        @Override
        public SearchResponse search(SearchQuery query) {
            queries.add(query);
            if (failure != null) {
                throw failure;
            }
            boolean windowed = query.filters().containsKey(DefaultDigestService.INDEXED_AT);
            List<SearchHit> hits = !windowed && withoutWindow != null ? withoutWindow : result;
            return new SearchResponse(hits, null, null, 1);
        }
    }

    private final InMemoryDigestRepository repository = new InMemoryDigestRepository();
    private final RecordingSearch search = new RecordingSearch();

    private static SearchHit hit(String entityId) {
        return new SearchHit(entityId + "_0", entityId, "kn_1", 0, "Title " + entityId, "text",
                "snippet", "uri://" + entityId, 1.0, Map.of());
    }

    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final TaskLibrary library = new TaskLibrary(PromptCatalog.bundled(), taskRepository);

    private final InMemoryChannelRepository channels = new InMemoryChannelRepository();
    private final InMemoryDeliveryRepository deliveries = new InMemoryDeliveryRepository();

    private DefaultDigestService service(StubSearchAgent agent) {
        DefaultDigestService svc = new DefaultDigestService(repository, search, agent, library, channels,
                new DefaultPublishingService(channels, deliveries));
        svc.consoleUrl = "http://console.test/";
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

        Object filter = search.queries.get(0).filters().get(DefaultDigestService.INDEXED_AT);
        Assertions.assertInstanceOf(Map.class, filter, "a window must be a range, not a term");
        Assertions.assertTrue(((Map<?, ?>) filter).containsKey("gte"));
    }

    @Test
    void aDigestWithNoWindowAppliesNoTimeFilter() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest(null, null, false, 10));

        svc.run(created.id());

        Assertions.assertFalse(
                search.queries.get(0).filters().containsKey(DefaultDigestService.INDEXED_AT));
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

        Assertions.assertEquals(20, search.queries.get(0).topK(), "5 requested x4 multiplier");
    }

    @Test
    void onlyNewOffReportsEverythingTrimmedToTopK() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, false, 2));
        search.result = List.of(hit("ent_a"), hit("ent_b"), hit("ent_c"));

        Assertions.assertEquals(2, svc.run(created.id()).items().size());
        Assertions.assertEquals(2, search.queries.get(0).topK(),
                "no over-fetch is needed without filtering");
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

    // ---- annotations -------------------------------------------------------------------------

    /** A job-fit-shaped reply: an array of objects, each naming the source it describes. */
    private static String scored(String body) {
        return "{\"postings\": [" + body + "]}";
    }

    @Test
    void aPerSourceReplyIsJoinedOntoTheItemItDescribes() {
        StubSearchAgent agent = new StubSearchAgent(scored(
                "{\"source\": 2, \"fit\": 9, \"reason\": \"Kafka and the JVM\"},"
                        + "{\"source\": 1, \"fit\": 3, \"reason\": \"mostly frontend\"}"));
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));
        search.result = List.of(hit("ent_a"), hit("ent_b"));

        DigestRun run = svc.run(created.id());

        // Source numbers are positional, so 2 describes the second item however the reply is ordered.
        Assertions.assertEquals(3L, run.items().get(0).annotations().get("fit"));
        Assertions.assertEquals(9L, run.items().get(1).annotations().get("fit"));
        Assertions.assertEquals("Kafka and the JVM", run.items().get(1).annotations().get("reason"));
    }

    @Test
    void aNullAnnotationIsOmittedRatherThanStoredEmpty() {
        // "concern": null is the model saying there is nothing to report; keeping it would make the
        // console render a labelled blank on most items.
        StubSearchAgent agent = new StubSearchAgent(
                scored("{\"source\": 1, \"fit\": 7, \"concern\": null}"));
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertEquals(java.util.Set.of("fit"), run.items().get(0).annotations().keySet());
    }

    @Test
    void anUnreadableReplyLeavesItemsUnannotatedRatherThanFailingTheRun() {
        StubSearchAgent agent = new StubSearchAgent("I could not score these, sorry.");
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertNull(run.error(), "the results are real and worth showing");
        Assertions.assertTrue(run.items().get(0).annotations().isEmpty());
        Assertions.assertEquals("I could not score these, sorry.", run.taskOutput(),
                "the reply is kept verbatim so a broken task can be diagnosed");
    }

    @Test
    void aSourceNumberOutsideTheBatchIsIgnored() {
        // The model can name a source that was cut for budget, or simply invent one. That costs the
        // annotation, not the run.
        StubSearchAgent agent = new StubSearchAgent(scored(
                "{\"source\": 9, \"fit\": 10},{\"source\": 0, \"fit\": 1},{\"source\": 1, \"fit\": 5}"));
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "job-fit", true, 10));
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertEquals(1, run.items().size());
        Assertions.assertEquals(5L, run.items().get(0).annotations().get("fit"));
    }

    @Test
    void aTaskThatDeclaresNoArrayAnnotatesNothing() {
        // "answer" replies in prose; there is nothing to join, and trying would be guesswork.
        StubSearchAgent agent = new StubSearchAgent(scored("{\"source\": 1, \"fit\": 9}"));
        DefaultDigestService svc = service(agent);
        Digest created = svc.create(digest("1d", "answer", true, 10));
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertTrue(run.items().get(0).annotations().isEmpty());
    }

    // ---- run counters ------------------------------------------------------------------------

    @Test
    void countsWhatWasFoundAndWhatWasAlreadySeen() {
        // Three outcomes otherwise look identical in the console: nothing matched, everything matched
        // was already seen, and something new turned up.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"), hit("ent_b"));
        svc.run(created.id());

        search.result = List.of(hit("ent_a"), hit("ent_b"), hit("ent_c"));
        DigestRun second = svc.run(created.id());

        Assertions.assertEquals(3, second.candidates());
        Assertions.assertEquals(2, second.suppressed());
        Assertions.assertEquals(1, second.items().size());
    }

    // ---- editing and history ------------------------------------------------------------------

    @Test
    void editingADigestDoesNotCostItItsMemory() {
        // The delete-and-recreate route this replaced dropped the runs, and the runs are the
        // already-seen set — so renaming a digest used to make it re-report its whole window.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        svc.run(created.id());

        svc.update(created.id(), new DigestPatch(Patched.of("Renamed"), null, null, null,
                null, Patched.of("7d"), null, null, null, null, null, null, null));
        DigestRun after = svc.run(created.id());

        Assertions.assertEquals("Renamed", svc.get(created.id()).orElseThrow().name());
        Assertions.assertEquals("7d", svc.get(created.id()).orElseThrow().window());
        Assertions.assertEquals("engineer", svc.get(created.id()).orElseThrow().query(),
                "a field the patch did not mention is untouched");
        Assertions.assertTrue(after.items().isEmpty(), "ent_a was already reported");
    }

    @Test
    void resettingHistoryReplaysTheBacklogWithoutDeletingTheRecord() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        svc.run(created.id());

        svc.resetHistory(created.id());
        DigestRun replayed = svc.run(created.id());

        Assertions.assertEquals(List.of("ent_a"),
                replayed.items().stream().map(DigestRun.Item::entityId).toList());
        Assertions.assertEquals(2, repository.findRuns(created.id(), 10).size(),
                "the earlier run is still in the history — only the seen-set was cleared");
    }

    @Test
    void anExplicitNullClearsTheFieldRatherThanBeingIgnored() {
        // Every console control that turns something off sends one of these. Treated as "unchanged",
        // a digest could be given a look-back window, or a task, and never have it taken away — and
        // the edit still answered 200.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", "job-fit", true, 10));

        Digest cleared = svc.update(created.id(), new DigestPatch(null, null, null, null, null,
                Patched.of(null), null, Patched.of(null), null, null, Patched.of(null), null, null));

        Assertions.assertNull(cleared.window(), "no time bound");
        Assertions.assertNull(cleared.taskId(), "results only");
        Assertions.assertNull(cleared.maxChunksPerEntity(), "back to the server default");
    }

    @Test
    void anAbsentFieldIsStillLeftAlone() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", "job-fit", true, 10));

        Digest renamed = svc.update(created.id(), new DigestPatch(Patched.of("Renamed"), null, null,
                null, null, null, null, null, null, null, null, null, null));

        Assertions.assertEquals("1d", renamed.window());
        Assertions.assertEquals("job-fit", renamed.taskId());
    }

    @Test
    void clearingAFieldThatHasNoOffFallsBackToItsDefault() {
        // topK, onlyNew and enabled are primitives on the digest: there is no "unset" to write.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, false, 3));

        Digest cleared = svc.update(created.id(), new DigestPatch(null, null, null, null, null, null,
                null, null, Patched.of(null), null, null, Patched.of(null), Patched.of(null)));

        Assertions.assertEquals(Digest.DEFAULT_TOP_K, cleared.topK());
        Assertions.assertTrue(cleared.onlyNew());
        Assertions.assertTrue(cleared.enabled());
    }

    @Test
    void anEditCannotLeaveADigestWithNothingToSearchFor() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.update(created.id(), new DigestPatch(null, Patched.of(""), null,
                        null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void aRunResolvesByIdOnlyForItsOwnDigest() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest mine = svc.create(digest("1d", null, true, 10));
        Digest other = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        DigestRun run = svc.run(mine.id());

        Assertions.assertTrue(svc.run(mine.id(), run.id()).isPresent());
        Assertions.assertTrue(svc.run(other.id(), run.id()).isEmpty(),
                "a stale link must not reach another digest's run");
    }

    @Test
    void aRunThatMatchedNothingIsDistinguishableFromOneThatSawItAllBefore() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));

        DigestRun empty = svc.run(created.id());

        Assertions.assertEquals(0, empty.candidates());
        Assertions.assertEquals(0, empty.suppressed());
    }

    @Test
    void anEmptyRunCountsWhatTheWindowCostIt() {
        // The window filters on indexedAt, so a source ingested once and then left alone falls out of
        // a short window and never comes back. Without this count that is indistinguishable from a
        // query that matches nothing, and the console tells the user to fix the wrong thing.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.withoutWindow = List.of(hit("ent_a"), hit("ent_b"));

        DigestRun empty = svc.run(created.id());

        Assertions.assertEquals(0, empty.candidates());
        Assertions.assertEquals(2, empty.outsideWindow());
        Assertions.assertEquals(2, search.queries.size(), "the recount is one extra search");
        Assertions.assertFalse(
                search.queries.get(1).filters().containsKey(DefaultDigestService.INDEXED_AT));
    }

    @Test
    void theRecountIsSkippedWhenItCouldExplainNothing() {
        // It only answers "did the window empty this run", so a run with results, and a digest with no
        // window at all, must not pay for a second search.
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest windowed = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));
        svc.run(windowed.id());
        Assertions.assertEquals(1, search.queries.size(), "a run with results explains itself");

        search.queries.clear();
        search.result = List.of();
        Digest unbounded = svc.create(digest(null, null, true, 10));
        DigestRun empty = svc.run(unbounded.id());

        Assertions.assertEquals(1, search.queries.size(), "there is no window to blame");
        Assertions.assertEquals(0, empty.outsideWindow());
    }

    // ---- publishing a run to channels ---------------------------------------------------------------

    private void channel(String id) {
        Instant now = Instant.now();
        channels.insert(new Channel(id, "Inbox " + id, ChannelType.EMAIL, null, Map.of(), true, ChannelStatus.ACTIVE,
                null, now, now));
    }

    private static DigestPatch sendTo(List<String> channelIds) {
        return new DigestPatch(null, null, null, null, null, null, null, null, null, null, null, null, null,
                Patched.of(channelIds));
    }

    @Test
    void aRunWithNewItemsIsQueuedOncePerChannel() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        channel("chn_a");
        channel("chn_b");
        Digest created = svc.update(svc.create(digest("1d", null, true, 10)).id(), sendTo(List.of("chn_a", "chn_b")));
        search.result = List.of(hit("ent_a"), hit("ent_b"));

        DigestRun run = svc.run(created.id());

        Assertions.assertEquals(2, deliveries.store.size());
        for (Delivery delivery : deliveries.store.values()) {
            Assertions.assertEquals(Delivery.Origin.DIGEST_RUN, delivery.origin().kind());
            Assertions.assertEquals(run.id(), delivery.origin().refId());
            Assertions.assertEquals("digestRun:" + run.id() + ":" + delivery.channelId(), delivery.dedupeKey());
            Assertions.assertEquals(2, delivery.message().items().size());
            Assertions.assertTrue(delivery.message().title().startsWith(created.name()), delivery.message().title());
            Assertions.assertEquals("http://console.test/digests/" + created.id(), delivery.message().link());
        }
    }

    @Test
    void aQuietRunSendsNothing() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        channel("chn_a");
        Digest created = svc.update(svc.create(digest("1d", null, true, 10)).id(), sendTo(List.of("chn_a")));
        search.result = List.of();

        svc.run(created.id());

        Assertions.assertTrue(deliveries.store.isEmpty(), "a daily 'nothing new' is noise");
    }

    @Test
    void aFailedRunSendsAFailureNotice() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        channel("chn_a");
        Digest created = svc.update(svc.create(digest("1d", null, true, 10)).id(), sendTo(List.of("chn_a")));
        search.failure = new IllegalStateException("OpenSearch is down");

        DigestRun run = svc.run(created.id());

        Assertions.assertNotNull(run.error());
        Delivery notice = deliveries.store.values().iterator().next();
        Assertions.assertTrue(notice.message().title().endsWith("failed"), notice.message().title());
        Assertions.assertTrue(notice.message().intro().contains("OpenSearch is down"), notice.message().intro());
    }

    @Test
    void aDigestWithNoChannelsSendsNothing() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));
        search.result = List.of(hit("ent_a"));

        svc.run(created.id());

        Assertions.assertTrue(deliveries.store.isEmpty());
    }

    @Test
    void anUnknownChannelIsRefused() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        Digest created = svc.create(digest("1d", null, true, 10));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.update(created.id(), sendTo(List.of("chn_missing"))));
        Assertions.assertTrue(svc.get(created.id()).orElseThrow().channelIds().isEmpty());
    }

    @Test
    void failingToQueueDoesNotFailTheRun() {
        DefaultDigestService svc = service(new StubSearchAgent(""));
        channel("chn_a");
        Digest created = svc.update(svc.create(digest("1d", null, true, 10)).id(), sendTo(List.of("chn_a")));
        channels.store.remove("chn_a"); // gone between configuring the digest and the run
        search.result = List.of(hit("ent_a"));

        DigestRun run = svc.run(created.id());

        Assertions.assertNull(run.error(), "the run happened; only its message was lost");
        Assertions.assertEquals(1, run.items().size());
        Assertions.assertTrue(deliveries.store.isEmpty());
    }
}
