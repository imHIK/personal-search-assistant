package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.RawItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filtering runs twice, a platform's early pass over its listing and then the connector's authoritative one,
 * and both call these methods so they cannot drift. Each dimension is independent, and an empty one is no
 * opinion. Exclude beats include, and a missing location or date keeps the posting.
 *
 * @param locations already lowercased
 * @param includeRemote a stated-remote posting that names no place matches; one that names a place still
 *     has to match the place terms
 */
public record BoardFilter(List<String> locations, List<String> titleInclude,
                          List<String> titleExclude, Duration maxAge, boolean includeRemote) {

    public static final BoardFilter NONE = new BoardFilter(List.of(), List.of(), List.of(), null, false);

    public static BoardFilter ofLocations(List<String> locations) {
        return new BoardFilter(locations, List.of(), List.of(), null, false);
    }

    public static BoardFilter ofTitles(List<String> include, List<String> exclude) {
        return new BoardFilter(List.of(), include, exclude, null, false);
    }

    public BoardFilter {
        locations = lower(locations);
        titleInclude = lower(titleInclude);
        titleExclude = lower(titleExclude);
        maxAge = maxAge == null || maxAge.isZero() || maxAge.isNegative() ? null : maxAge;
    }

    public boolean isEmpty() {
        return locations.isEmpty() && titleInclude.isEmpty() && titleExclude.isEmpty() && maxAge == null;
    }

    /**
     * The half safe on a listing: the title is the same string there as in the detail, whereas a listed
     * location may be a bare city or a count.
     */
    public boolean matchesTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (String term : titleExclude) {
            if (t.contains(term)) {
                return false;
            }
        }
        if (titleInclude.isEmpty()) {
            return true;
        }
        for (String term : titleInclude) {
            if (t.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(RawItem item) {
        if (item == null) {
            return false;
        }
        if (item.deleted()) {
            return true; // a tombstone must always pass, or the deletion is never applied
        }
        if (!matchesTitle(item.title())) {
            return false;
        }
        if (!matchesLocation(item)) {
            return false;
        }
        return matchesAge(item);
    }

    private boolean matchesLocation(RawItem item) {
        if (locations.isEmpty()) {
            return true;
        }
        Map<String, Object> metadata = item.metadata();
        Object place = metadata == null ? null : metadata.get("location");
        String location = place == null ? null : place.toString();
        if (includeRemote && Boolean.TRUE.equals(metadata == null ? null : metadata.get("remote"))
                && AtsNormalization.placeWithoutRemote(location).isEmpty()) {
            return true;
        }
        return AtsNormalization.matchesLocation(location, locations);
    }

    /**
     * An absent date keeps the posting: Lever and SmartRecruiters publish only a first-posted stamp, and some
     * boards none.
     */
    private boolean matchesAge(RawItem item) {
        if (maxAge == null) {
            return true;
        }
        Instant posted = postedAt(item);
        return posted == null || !posted.isBefore(Instant.now().minus(maxAge));
    }

    private static Instant postedAt(RawItem item) {
        Object stamped = item.metadata() == null ? null : item.metadata().get("postedAt");
        if (stamped instanceof Instant instant) {
            return instant;
        }
        // metadata is what a platform sets explicitly, so it wins; modifiedAt is the fallback.
        return item.modifiedAt();
    }

    private static List<String> lower(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
