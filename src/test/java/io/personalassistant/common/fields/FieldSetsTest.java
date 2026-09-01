package io.personalassistant.common.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.enums.SourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Named, connector-scoped field sets.
 *
 * <p>Replaces two hardcoded lists that could not be scoped and, in one case, had silently rotted: the
 * prompt's locator constant read {@code ("sheet", "page", "headingPath")} while nothing in the pipeline
 * produced {@code sheet} or {@code page}, and {@code rowRange} — the one structural locator that <em>is</em>
 * produced — was absent, so it never reached a prompt. {@link #theShippedLocatorSetIncludesTheKeyThePipelineActuallyProduces()}
 * is the regression guard for that.
 */
class FieldSetsTest {

    private static FieldSets from(String json, Path dir) throws IOException {
        Path file = dir.resolve("field-sets.json");
        Files.writeString(file, json);
        FieldSets sets = new FieldSets();
        sets.overridePath = Optional.of(file.toString());
        sets.load();
        return sets;
    }

    // ---- the shipped file --------------------------------------------------------------------

    @Test
    void loadsTheBundledSets() {
        FieldSets sets = FieldSets.bundled();

        assertTrue(sets.setNames().contains(FieldSets.EMBED_CONTEXT), sets.setNames().toString());
        assertTrue(sets.setNames().contains(FieldSets.PROMPT_LOCATOR));
    }

    /**
     * The shipped default must stay {@code ["title"]}: it is what existing vectors were built from, so
     * changing it silently would leave the index inconsistent with the query path until a full re-index.
     */
    @Test
    void theShippedEmbedContextDefaultMatchesWhatIsAlreadyIndexed() {
        assertEquals(List.of("title"), FieldSets.bundled().resolve(FieldSets.EMBED_CONTEXT, null));
    }

    @Test
    void theShippedLocatorSetIncludesTheKeyThePipelineActuallyProduces() {
        List<String> locator = FieldSets.bundled().resolve(FieldSets.PROMPT_LOCATOR);

        assertTrue(locator.contains("rowRange"),
                "rowRange is emitted by TableAwareChunkingStrategy and is the only structural locator "
                        + "the pipeline currently produces; leaving it out is why locators never showed: "
                        + locator);
        assertTrue(locator.contains("headingPath"), locator.toString());
    }

    // ---- scoping -----------------------------------------------------------------------------

    @Test
    void aPerConnectorListWinsEntire(@TempDir Path dir) throws IOException {
        FieldSets sets = from("""
                {"fieldSets":{"s":{"default":["title"],"bySourceType":{"GMAIL":["from","to"]}}}}
                """, dir);

        assertEquals(List.of("from", "to"), sets.resolve("s", SourceType.GMAIL),
                "the connector list replaces the default rather than merging with it");
        assertEquals(List.of("title"), sets.resolve("s", SourceType.LOCAL_FS),
                "a connector with no entry falls back to the default");
        assertEquals(List.of("title"), sets.resolve("s", null));
    }

    @Test
    void anUnknownSetWarnsAndYieldsNothing(@TempDir Path dir) throws IOException {
        FieldSets sets = from("{\"fieldSets\":{\"s\":{\"default\":[\"title\"]}}}", dir);

        assertEquals(List.of(), sets.resolve("no-such-set", SourceType.GMAIL),
                "a missing field set degrades retrieval slightly; taking indexing offline would be worse");
    }

    /**
     * Config may legitimately be written ahead of the connector that will use it, so an unrecognised
     * source type is ignored with a warning rather than failing startup.
     */
    @Test
    void anUnknownSourceTypeIsIgnoredRatherThanFatal(@TempDir Path dir) throws IOException {
        FieldSets sets = from("""
                {"fieldSets":{"s":{"default":["title"],"bySourceType":{"NOT_A_CONNECTOR":["x"]}}}}
                """, dir);

        assertEquals(List.of("title"), sets.resolve("s", SourceType.GMAIL));
    }

    @Test
    void blankAndNonTextEntriesAreDropped(@TempDir Path dir) throws IOException {
        FieldSets sets = from("{\"fieldSets\":{\"s\":{\"default\":[\"title\",\"  \",42,\" uri \"]}}}", dir);

        assertEquals(List.of("title", "uri"), sets.resolve("s"), "values are trimmed and filtered");
    }

    @Test
    void aSetWithNoDefaultResolvesToEmpty(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), from("{\"fieldSets\":{\"s\":{}}}", dir).resolve("s"));
    }

    // ---- failure modes -----------------------------------------------------------------------

    @Test
    void anEmptyFileFailsToLoad(@TempDir Path dir) {
        assertTrue(assertThrows(IllegalStateException.class, () -> from("{\"fieldSets\":{}}", dir))
                .getMessage().contains("at least one"));
    }

    @Test
    void malformedJsonFailsLoudly(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> from("{not json", dir));
    }

    @Test
    void anUnreadableOverridePathFailsLoudly() {
        FieldSets sets = new FieldSets();
        sets.overridePath = Optional.of("/nonexistent/field-sets.json");

        assertTrue(assertThrows(IllegalStateException.class, sets::load).getMessage().contains("not readable"));
    }
}
