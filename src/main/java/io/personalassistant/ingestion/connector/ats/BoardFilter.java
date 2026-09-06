package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.RawItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which postings a job-board knowledge wants, in one value.
 *
 * <p>It exists because filtering happens in <em>two</em> places that must agree. The connector applies
 * it authoritatively in {@code grab}, after a platform has produced {@link RawItem}s; a platform may
 * also apply the cheap half of it to its listing, before paying for a per-posting detail call. Two
 * copies of the rules would drift, and a platform that filtered slightly harder than the connector
 * would silently lose postings nobody could account for — so both call the same methods here.
 *
 * <h2>Why this and not more embedding budget</h2>
 * Every posting kept is parsed, chunked and <strong>embedded</strong>, and hosted embedding quotas are
 * counted per chunk. Measured over 71 company boards: 57,389 postings ≈ 355,000 chunks; the location
 * terms alone cut that to ≈ 34,000; adding a title include/exclude pair cuts it to ≈ 7,400. The title
 * filter is the strongest lever because {@code title} is the one useful field every platform puts in
 * its <em>listing</em> — so it also removes the detail call, which location often cannot (Workday's
 * listing may say only {@code "5 Locations"}).
 *
 * <h2>Rules</h2>
 * Each dimension is independent and an empty one is "no opinion", never "match nothing". Within a
 * dimension the terms are alternatives, matched as case-insensitive substrings — the same shape as the
 * location terms this generalises, so a user who understands one understands all of them.
 *
 * <p>Two deliberate asymmetries:
 * <ul>
 *   <li><strong>Exclude beats include.</strong> "Software Engineering Manager" matches an include of
 *       {@code software engineer} and an exclude of {@code manager}; the exclude wins, because the
 *       exclude list is how a user says "not this kind of role" and it would be useless otherwise.</li>
 *   <li><strong>A missing value keeps the posting.</strong> No location, no posted date — any doubt
 *       runs it, matching {@code AtsNormalization.matchesLocation} and {@code IngestionJob}'s rule.
 *       Boards leave these blank often enough that the alternative loses real roles on the strength of
 *       an absent field.</li>
 * </ul>
 *
 * @param locations     place terms; a posting matches if its location contains any, already lowercased
 * @param titleInclude  role terms; a posting matches if its title contains any
 * @param titleExclude  role terms; a posting is dropped if its title contains any
 * @param maxAge        keep only postings posted within this window; null for no limit
 * @param includeRemote when true a remote posting satisfies {@link #locations} however it is filed —
 *                      an OR with the place terms, not a filter of its own, because "remote" is a
 *                      place a role can be rather than a separate property to require
 */
public record BoardFilter(List<String> locations, List<String> titleInclude,
                          List<String> titleExclude, Duration maxAge, boolean includeRemote) {

    /** Keeps everything. Used by the resolution path and by tests. */
    public static final BoardFilter NONE = new BoardFilter(List.of(), List.of(), List.of(), null, false);

    /** Place terms only — the shape this generalised, and the one most callers and tests want. */
    public static BoardFilter ofLocations(List<String> locations) {
        return new BoardFilter(locations, List.of(), List.of(), null, false);
    }

    /** Role terms only, include then exclude. */
    public static BoardFilter ofTitles(List<String> include, List<String> exclude) {
        return new BoardFilter(List.of(), include, exclude, null, false);
    }

    public BoardFilter {
        locations = lower(locations);
        titleInclude = lower(titleInclude);
        titleExclude = lower(titleExclude);
        maxAge = maxAge == null || maxAge.isZero() || maxAge.isNegative() ? null : maxAge;
    }

    /** True when nothing is constrained — lets a platform skip the work of narrowing. */
    public boolean isEmpty() {
        return locations.isEmpty() && titleInclude.isEmpty() && titleExclude.isEmpty() && maxAge == null;
    }

    /**
     * The half a platform can apply to a listing entry, where only the title is reliably present.
     *
     * <p>Deliberately does <em>not</em> consider location: several listings carry a bare city or a
     * count, and dropping on that is the silent-miss trap documented on {@code WorkdayPlatform}. Title
     * has no such problem — it is the same string in the listing and the detail.
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

    /** The authoritative test, run by the connector once a platform has produced the full item. */
    public boolean matches(RawItem item) {
        if (item == null) {
            return false;
        }
        if (item.deleted()) {
            return true; // a tombstone must always through, or the deletion is never applied
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
        if (includeRemote && metadata != null && Boolean.TRUE.equals(metadata.get("remote"))) {
            return true; // stated remote: the place terms do not apply to it
        }
        Object place = metadata == null ? null : metadata.get("location");
        return AtsNormalization.matchesLocation(place == null ? null : place.toString(), locations);
    }

    /**
     * A posting is too old when it states a date outside the window. An <em>absent</em> date keeps it:
     * Lever and SmartRecruiters publish only a first-posted stamp and some boards publish none at all,
     * so treating null as "old" would silently empty those boards.
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
        // modifiedAt is the same value on every ATS platform, but metadata is what a platform sets
        // explicitly, so it is preferred and this is the fallback.
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
