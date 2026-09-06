package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The filter rules, including the three places where "absent" deliberately means "keep". */
class BoardFilterTest {

    private static RawItem posting(String title, String location, Boolean remote, Instant postedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        if (location != null) {
            metadata.put("location", location);
        }
        if (remote != null) {
            metadata.put("remote", remote);
        }
        if (postedAt != null) {
            metadata.put("postedAt", postedAt);
        }
        return new RawItem("id-" + title, EntityType.JOB_POSTING, "text/html", title, "u", "c",
                postedAt, Map.of(), "body", null, metadata, null, false);
    }

    private static RawItem posting(String title, String location) {
        return posting(title, location, null, Instant.now());
    }

    // ---- title ---------------------------------------------------------------------------------

    @Test
    void anEmptyIncludeListKeepsEverything() {
        // "No opinion", never "match nothing" — otherwise adding an exclude would empty the board.
        BoardFilter f = BoardFilter.ofTitles(List.of(), List.of("manager"));

        Assertions.assertTrue(f.matchesTitle("Backend Engineer"));
        Assertions.assertFalse(f.matchesTitle("Engineering Manager"));
    }

    @Test
    void excludeBeatsInclude() {
        // "Software Engineering Manager" matches both lists. The exclude has to win or it says nothing.
        BoardFilter f = BoardFilter.ofTitles(List.of("software engineer"), List.of("manager"));

        Assertions.assertTrue(f.matchesTitle("Senior Software Engineer, Payments"));
        Assertions.assertFalse(f.matchesTitle("Software Engineering Manager"));
    }

    @Test
    void titleMatchingIsCaseInsensitiveSubstring() {
        BoardFilter f = BoardFilter.ofTitles(List.of("SDE"), List.of());

        Assertions.assertTrue(f.matchesTitle("sde ii - retail"));
        Assertions.assertTrue(f.matchesTitle("SDE-2"));
    }

    @Test
    void aTitleFilterIsExactBecauseATitleIsAlwaysPresent() {
        // Unlike location and date, there is no "absent" case to be generous about.
        BoardFilter f = BoardFilter.ofTitles(List.of("engineer"), List.of());

        Assertions.assertFalse(f.matchesTitle(null));
        Assertions.assertFalse(f.matchesTitle(""));
    }

    // ---- location and remote -------------------------------------------------------------------

    @Test
    void aPostingWithNoLocationSurvivesALocationFilter() {
        // Boards leave it blank often enough that dropping would lose real roles.
        BoardFilter f = BoardFilter.ofLocations(List.of("bengaluru"));

        Assertions.assertTrue(f.matches(posting("Backend Engineer", null)));
        Assertions.assertFalse(f.matches(posting("Backend Engineer", "Dublin, Ireland")));
    }

    @Test
    void includeRemoteAdmitsARemoteRoleFiledAnywhere() {
        BoardFilter on = new BoardFilter(List.of("bengaluru"), List.of(), List.of(), null, true);
        BoardFilter off = new BoardFilter(List.of("bengaluru"), List.of(), List.of(), null, false);
        RawItem remoteInLondon = posting("Backend Engineer", "London, UK", true, Instant.now());

        Assertions.assertTrue(on.matches(remoteInLondon), "remote is a place a role can be");
        Assertions.assertFalse(off.matches(remoteInLondon));
    }

    @Test
    void includeRemoteDoesNotSmuggleARoleThroughTheTitleFilter() {
        // It is an OR with the PLACE terms only; every other dimension still applies.
        BoardFilter f = new BoardFilter(List.of("bengaluru"), List.of("engineer"), List.of(), null, true);

        Assertions.assertFalse(f.matches(posting("Account Executive", "London, UK", true, Instant.now())));
    }

    // ---- age -----------------------------------------------------------------------------------

    @Test
    void anAgeLimitDropsOnlyPostingsThatStateAnOlderDate() {
        BoardFilter f = new BoardFilter(List.of(), List.of(), List.of(), Duration.ofDays(14), false);
        Instant old = Instant.now().minus(Duration.ofDays(30));

        Assertions.assertTrue(f.matches(posting("A", "Pune", null, Instant.now())));
        Assertions.assertFalse(f.matches(posting("B", "Pune", null, old)));
    }

    @Test
    void aPostingWithNoDateSurvivesAnAgeLimit() {
        // Lever and SmartRecruiters publish only a first-posted stamp, some boards publish none —
        // treating null as old would silently empty those boards.
        BoardFilter f = new BoardFilter(List.of(), List.of(), List.of(), Duration.ofDays(14), false);

        Assertions.assertTrue(f.matches(posting("A", "Pune", null, null)));
    }

    @Test
    void aZeroOrNegativeAgeMeansNoLimitRatherThanNothing() {
        Assertions.assertNull(new BoardFilter(List.of(), List.of(), List.of(), Duration.ZERO, false).maxAge());
        Assertions.assertNull(
                new BoardFilter(List.of(), List.of(), List.of(), Duration.ofDays(-1), false).maxAge());
    }

    // ---- shape ---------------------------------------------------------------------------------

    @Test
    void aTombstoneAlwaysPasses() {
        // Filtering out a deletion would leave the entity indexed forever.
        BoardFilter f = new BoardFilter(List.of("bengaluru"), List.of("engineer"), List.of(),
                Duration.ofDays(1), false);

        Assertions.assertTrue(f.matches(RawItem.tombstone("gone")));
    }

    @Test
    void termsAreLowercasedAndDeduplicatedOnConstruction() {
        BoardFilter f = new BoardFilter(List.of("Bengaluru", "BENGALURU", " bangalore "),
                List.of("Engineer"), List.of(), null, false);

        Assertions.assertEquals(List.of("bengaluru", "bangalore"), f.locations());
        Assertions.assertEquals(List.of("engineer"), f.titleInclude());
    }

    @Test
    void anEmptyFilterIsRecognisedSoPlatformsCanSkipTheWork() {
        Assertions.assertTrue(BoardFilter.NONE.isEmpty());
        Assertions.assertFalse(BoardFilter.ofTitles(List.of("engineer"), List.of()).isEmpty());
        Assertions.assertFalse(
                new BoardFilter(List.of(), List.of(), List.of(), Duration.ofDays(7), false).isEmpty());
    }
}
