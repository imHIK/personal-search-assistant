package io.personalassistant.retrieval;

import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Nudges fresher results up, so between two near-equal matches the newer one wins.
 *
 * <p>A multiplier, not a filter or a sort: relevance must still decide the order, because a posting from
 * yesterday that does not fit is worse than a fitting one from last week. With the shipped weight a
 * brand-new item gains at most 10%, which reorders near-ties and nothing else, and the gain halves every
 * half-life so age fades out rather than dropping off a cliff.
 *
 * <p>Which metadata carries "the date" is a {@link FieldSets#RECENCY} field set, tried in order, first
 * parseable value wins. It ships as {@code postedAt} only — a job posting's date is what the user means
 * by fresh, whereas a file's {@code modifiedAt} moves on any save and would reward churn. An item with
 * no such field is left exactly as it was. Results carry no source type, so only the set's default list
 * applies here.
 */
@ApplicationScoped
public class RecencyBoost {

    private static final double MILLIS_PER_DAY = 86_400_000.0;

    private final FieldSets fieldSets;

    /** Package-private so tests can pin "now". */
    Clock clock = Clock.systemUTC();

    /** Largest share a brand-new item's score can grow by; 0 turns the boost off. */
    @ConfigProperty(name = "app.search.recency.weight", defaultValue = "0.1")
    double weight;

    /** Days after which the boost has halved. */
    @ConfigProperty(name = "app.search.recency.half-life-days", defaultValue = "14")
    int halfLifeDays;

    @Inject
    public RecencyBoost(FieldSets fieldSets) {
        this.fieldSets = fieldSets;
    }

    /** Test-friendly constructor; CDI uses the one above and injects the fields. */
    public RecencyBoost(FieldSets fieldSets, double weight, int halfLifeDays, Clock clock) {
        this(fieldSets);
        this.weight = weight;
        this.halfLifeDays = halfLifeDays;
        this.clock = clock;
    }

    /** {@code results} rescored for freshness and re-sorted, best first; unchanged when nothing carries a date. */
    public List<SearchHit> apply(List<SearchHit> results) {
        if (results == null || results.isEmpty() || weight <= 0 || halfLifeDays <= 0) {
            return results == null ? List.of() : results;
        }
        List<String> fields = fieldSets.resolve(FieldSets.RECENCY);
        if (fields.isEmpty()) {
            return results;
        }
        Instant now = clock.instant();
        boolean changed = false;
        List<SearchHit> boosted = new ArrayList<>(results.size());
        for (SearchHit hit : results) {
            Instant when = dateOf(hit, fields);
            if (when == null) {
                boosted.add(hit);
                continue;
            }
            // A future date (a clock skew, a scheduled posting) counts as brand new, never as extra-new.
            double ageDays = Math.max(now.toEpochMilli() - when.toEpochMilli(), 0) / MILLIS_PER_DAY;
            double factor = 1 + weight * Math.pow(0.5, ageDays / halfLifeDays);
            boosted.add(hit.withScore(hit.score() * factor).withRanking(hit.ranking().withRecencyFactor(factor)));
            changed = true;
        }
        if (!changed) {
            return results;
        }
        // Stable, so undated results keep their relative order.
        boosted.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return List.copyOf(boosted);
    }

    private static Instant dateOf(SearchHit hit, List<String> fields) {
        if (hit.metadata() == null) {
            return null;
        }
        for (String field : fields) {
            Instant parsed = parse(hit.metadata().get(field));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** ISO instants, offsets or plain dates, or epoch millis; anything else is "no date" rather than an error. */
    private static Instant parse(Object value) {
        if (value instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException notAnOffset) {
                try {
                    return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException notADate) {
                    return null;
                }
            }
        }
    }
}
