package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Grouping rules, and the properties that stop collapsing from hiding a real result. */
class DuplicateCollapserTest {

    private final DuplicateCollapser collapser = new DuplicateCollapser(5, 0.85, 100);

    private static SearchHit hit(String chunkId, String text, double score, Map<String, Object> metadata) {
        return new SearchHit(chunkId, "ent_" + chunkId, "kn_1", 0, "Title " + chunkId, text,
                "snippet", "uri://" + chunkId, score, metadata);
    }

    private static SearchHit hit(String chunkId, String text, double score) {
        return hit(chunkId, text, score, Map.of());
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::chunkId).toList();
    }

    @Test
    void identicalTextCollapsesEvenAcrossKnowledges() {
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "The quarterly revenue report for the third quarter of the year", 1.0),
                hit("b", "The quarterly revenue report for the third quarter of the year", 0.9),
                hit("c", "An entirely unrelated document about office furniture procurement", 0.8)));

        Assertions.assertEquals(List.of("a", "c"), ids(out));
    }

    @Test
    void formattingDifferencesDoNotPreventAnExactMatch() {
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "Quarterly Revenue: Q3 (final)", 1.0),
                hit("b", "quarterly revenue  q3   final", 0.9)));

        Assertions.assertEquals(List.of("a"), ids(out));
    }

    @Test
    void anEqualDedupeKeyCollapsesDespiteDifferentWording() {
        Map<String, Object> key = Map.of("dedupeKey", "acme|senior-backend-engineer|remote-us");
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "We are hiring a senior backend engineer to build our platform", 1.0, key),
                hit("b", "Join Acme as a Senior Backend Engineer. Remote, US.", 0.9, key)));

        Assertions.assertEquals(List.of("a"), ids(out));
    }

    @Test
    void nearIdenticalTextCollapsesWhenOnlyAHeaderDiffers() {
        String body = String.join(" ",
                "we are looking for an experienced platform engineer to own our kubernetes estate",
                "you will work with terraform and go and help us scale to the next order of magnitude");
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", body, 1.0),
                hit("b", "Posted via JobsRUs. " + body, 0.9)));

        Assertions.assertEquals(List.of("a"), ids(out));
    }

    @Test
    void twoDifferentRolesAtTheSameCompanyAreNotCollapsed() {
        // The failure mode that rules out embedding cosine: these are semantically very close, but
        // hiding one is worse than showing both.
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "Senior Backend Engineer working on billing systems in Go and Postgres", 1.0),
                hit("b", "Senior Frontend Engineer working on the design system in React and CSS", 0.9)));

        Assertions.assertEquals(List.of("a", "b"), ids(out));
    }

    @Test
    void keepsTheHigherRankedSourceButAtTheGroupsPosition() {
        // A posting found on an aggregator first and the company's own board second should keep the
        // canonical listing — without that listing being dragged down to the aggregator's rank.
        Map<String, Object> aggregator = Map.of("dedupeKey", "acme|engineer|london", "sourceRank", 10);
        Map<String, Object> direct = Map.of("dedupeKey", "acme|engineer|london", "sourceRank", 100);
        List<SearchHit> out = collapser.collapse(List.of(
                hit("aggregator", "Engineer wanted at Acme in London", 1.0, aggregator),
                hit("direct", "Engineer, Acme, London", 0.4, direct)));

        Assertions.assertEquals(List.of("direct"), ids(out));
        Assertions.assertEquals(1.0, out.get(0).score(),
                "the kept member must inherit the group's best score or collapsing reorders results");
    }

    @Test
    void withNoSourceRankTheBestRankedMemberIsKept() {
        Map<String, Object> key = Map.of("dedupeKey", "acme|engineer|london");
        List<SearchHit> out = collapser.collapse(List.of(
                hit("first", "Engineer wanted at Acme in London", 1.0, key),
                hit("second", "Engineer, Acme, London", 0.4, key)));

        Assertions.assertEquals(List.of("first"), ids(out));
    }

    @Test
    void rankOrderIsPreserved() {
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "alpha document about one subject entirely of its own", 1.0),
                hit("b", "beta document about a different subject entirely of its own", 0.9),
                hit("c", "alpha document about one subject entirely of its own", 0.8),
                hit("d", "gamma document about a third subject entirely of its own", 0.7)));

        Assertions.assertEquals(List.of("a", "b", "d"), ids(out));
    }

    @Test
    void emptyAndSingletonInputsPassThrough() {
        Assertions.assertEquals(List.of(), collapser.collapse(List.of()));
        Assertions.assertEquals(List.of(), collapser.collapse(null));
        List<SearchHit> one = List.of(hit("a", "text", 1.0));
        Assertions.assertEquals(one, collapser.collapse(one));
    }

    @Test
    void hitsWithNoTextAreNotAllTreatedAsTheSameThing() {
        // Blank text must not become a grouping key of its own, or every text-less hit collapses to one.
        List<SearchHit> out = collapser.collapse(List.of(
                hit("a", "", 1.0), hit("b", "", 0.9), hit("c", null, 0.8)));

        Assertions.assertEquals(List.of("a", "b", "c"), ids(out));
    }
}
