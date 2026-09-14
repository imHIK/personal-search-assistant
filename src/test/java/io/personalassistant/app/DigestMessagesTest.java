package io.personalassistant.app;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.SyncSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** What a digest run says when published, and whether it says anything at all. */
class DigestMessagesTest {

    private static Digest digest(boolean onlyNew) {
        Instant now = Instant.now();
        return new Digest("dig_1", "New roles", "roles", null, List.of(), Map.of(), null, SyncSchedule.NONE, null,
                10, false, 1, onlyNew, true, null, now, now);
    }

    private static DigestRun run(List<DigestRun.Item> items, String taskOutput, String error) {
        return new DigestRun("run_1", "dig_1", Instant.now(), items, taskOutput, error);
    }

    private static DigestRun.Item item(String id, Map<String, Object> annotations) {
        return new DigestRun.Item(id, id + "_0", "Title " + id, "https://jobs.example/" + id, 1.0, "snippet " + id,
                annotations);
    }

    @Test
    void onlyRunsThatFoundSomethingOrFailedAreWorthSending() {
        Assertions.assertFalse(DigestMessages.worthSending(run(List.of(), null, null)));
        Assertions.assertTrue(DigestMessages.worthSending(run(List.of(item("a", Map.of())), null, null)));
        Assertions.assertTrue(DigestMessages.worthSending(run(List.of(), null, "search failed")));
    }

    @Test
    void theTitleCountsWhatWasFound() {
        DigestRun two = run(List.of(item("a", Map.of()), item("b", Map.of())), null, null);
        DigestRun one = run(List.of(item("a", Map.of())), null, null);

        Assertions.assertEquals("New roles — 2 new results", DigestMessages.forRun(digest(true), two, null).title());
        Assertions.assertEquals("New roles — 1 result", DigestMessages.forRun(digest(false), one, null).title(),
                "a digest that repeats results must not call them new");
    }

    @Test
    void itemsCarryTheirAnnotationsUnderReadableLabels() {
        PublishMessage message = DigestMessages.forRun(digest(true),
                run(List.of(item("a", Map.of("fit", 8L, "next_step", "apply"))), "{\"postings\": []}", null), null);

        PublishMessage.Item only = message.items().get(0);
        Assertions.assertEquals("Title a", only.title());
        Assertions.assertEquals("https://jobs.example/a", only.uri());
        Assertions.assertEquals("snippet a", only.text());
        Assertions.assertEquals(Map.of("Fit", 8L, "Next step", "apply"), only.fields());
        Assertions.assertNull(message.summary(), "the reply was read onto the items; repeating it is noise");
    }

    @Test
    void theTaskReplyIsTheSummaryWhenNothingWasAnnotated() {
        PublishMessage message = DigestMessages.forRun(digest(true),
                run(List.of(item("a", Map.of())), "Two **strong** fits [1].", null), null);

        Assertions.assertEquals("Two **strong** fits [1].", message.summary());
    }

    @Test
    void aFailedRunSaysWhy() {
        PublishMessage message = DigestMessages.forRun(digest(true), run(List.of(), null, "OpenSearch is down"), null);

        Assertions.assertEquals("New roles failed", message.title());
        Assertions.assertTrue(message.intro().contains("OpenSearch is down"), message.intro());
        Assertions.assertTrue(message.items().isEmpty());
    }

    @Test
    void theLinkPointsBackToTheDigest() {
        DigestRun found = run(List.of(item("a", Map.of())), null, null);

        Assertions.assertEquals("http://localhost:8080/digests/dig_1",
                DigestMessages.forRun(digest(true), found, "http://localhost:8080//").link());
        Assertions.assertNull(DigestMessages.forRun(digest(true), found, " ").link());
    }

    @Test
    void labelsReadLikeTheConsole() {
        Assertions.assertEquals("Fit", DigestMessages.label("fit"));
        Assertions.assertEquals("Next step", DigestMessages.label("next_step"));
        Assertions.assertEquals("Next Step", DigestMessages.label("nextStep"));
    }
}
