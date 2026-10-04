package io.personalassistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecencyBoostTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    private final RecencyBoost boost =
            new RecencyBoost(FieldSets.bundled(), 0.1, 14, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SearchHit posting(String id, double score, Object postedAt) {
        Map<String, Object> metadata = postedAt == null ? Map.of() : Map.of("postedAt", postedAt);
        return new SearchHit(id + "_0", id, "kn", 0, "t", "x", "s", "u", score, metadata);
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::entityId).toList();
    }

    @Test
    void aFreshPostingWinsANearTie() {
        List<SearchHit> out = boost.apply(List.of(
                posting("old", 1.00, "2026-06-01T00:00:00Z"),
                posting("fresh", 0.97, "2026-09-14T00:00:00Z")));

        assertEquals(List.of("fresh", "old"), ids(out));
    }

    @Test
    void freshnessCannotOverruleAClearlyBetterMatch() {
        List<SearchHit> out = boost.apply(List.of(
                posting("relevant", 1.00, "2026-06-01T00:00:00Z"),
                posting("fresh", 0.80, "2026-09-15T00:00:00Z")));

        assertEquals(List.of("relevant", "fresh"), ids(out), "a brand-new item gains at most 10%");
    }

    @Test
    void theBoostHalvesEveryHalfLife() {
        SearchHit fourteenDaysOld = boost.apply(List.of(posting("p", 1.0, "2026-09-01T00:00:00Z"))).get(0);

        assertEquals(1.05, fourteenDaysOld.score(), 1e-9);
        assertEquals(1.05, fourteenDaysOld.ranking().recencyFactor(), 1e-9, "the multiplier is recorded");
        assertEquals(1.0, fourteenDaysOld.ranking().groupedScore(), 1e-9, "and the score it was applied to");
    }

    @Test
    void undatedOrUnreadableResultsAreLeftAsTheyWere() {
        List<SearchHit> undated = List.of(posting("a", 1.0, null), posting("b", 0.9, "not a date"));

        assertSame(undated, boost.apply(undated));
    }

    @Test
    void plainDatesAndEpochMillisAreUnderstood() {
        List<SearchHit> out = boost.apply(List.of(
                posting("date", 1.0, "2026-09-15"),
                posting("millis", 1.0, NOW.toEpochMilli())));

        assertEquals(1.1, out.get(0).score(), 1e-9);
        assertEquals(1.1, out.get(1).score(), 1e-9);
    }

    @Test
    void aWeightOfZeroTurnsItOff() {
        RecencyBoost off = new RecencyBoost(FieldSets.bundled(), 0, 14, Clock.fixed(NOW, ZoneOffset.UTC));
        List<SearchHit> results = List.of(posting("old", 1.0, "2026-01-01T00:00:00Z"),
                posting("fresh", 0.99, "2026-09-15T00:00:00Z"));

        assertSame(results, off.apply(results));
    }
}
