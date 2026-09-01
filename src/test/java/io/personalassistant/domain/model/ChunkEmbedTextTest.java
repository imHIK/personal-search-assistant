package io.personalassistant.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What actually gets embedded, as opposed to what gets stored.
 *
 * <p>Only the chunk body was ever embedded, so the title was invisible to the vector leg. A chunk holding
 * nothing but table rows — {@code "17 Dussehra 20/10/2026 Tuesday"} — shares no term and no semantic
 * signal with a document called {@code public_holidays_2026.pdf}, so a query about holidays never
 * retrieved it even though it was exactly what the user asked for. Prefixing the title (and any
 * structural locator) is what links the two.
 */
class ChunkEmbedTextTest {

    private static Chunk chunk(String text, Map<String, Object> metadata) {
        return new Chunk("ent_1_3", "ent_1", "kn_1", "root", SourceType.LOCAL_FS, 3, text, 10, null,
                "public_holidays_2026.pdf", "file:///holidays.pdf", metadata);
    }

    private static Chunk rows() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sheet", "2026");
        metadata.put("headingPath", "Holidays");
        metadata.put("checksum", "abc123");
        return chunk("17 Dussehra 20/10/2026 Tuesday", metadata);
    }

    @Test
    void prefixesTheConfiguredFieldsAheadOfTheBody() {
        String embedded = rows().embedText(List.of("title", "sheet", "headingPath"));

        assertTrue(embedded.startsWith("title: public_holidays_2026.pdf"), embedded);
        assertTrue(embedded.contains("sheet: 2026"));
        assertTrue(embedded.contains("headingPath: Holidays"));
        assertTrue(embedded.endsWith("17 Dussehra 20/10/2026 Tuesday"),
                "the body must remain the tail of the embedded string: " + embedded);
    }

    @Test
    void honoursTheConfiguredOrderAndOmitsWhatIsNotListed() {
        String embedded = rows().embedText(List.of("sheet", "title"));

        assertTrue(embedded.indexOf("sheet:") < embedded.indexOf("title:"), embedded);
        assertFalse(embedded.contains("headingPath"), "only listed fields are used");
        assertFalse(embedded.contains("checksum"), "machinery is not a retrieval signal");
    }

    /** A field only some formats produce must not become a hole in the embedded text. */
    @Test
    void skipsFieldsThatAreAbsentOrBlank() {
        Chunk plain = chunk("body", Map.of("sheet", "   "));

        String embedded = plain.embedText(List.of("title", "sheet", "page", "nope"));

        assertEquals("title: public_holidays_2026.pdf\n\nbody", embedded);
    }

    @Test
    void anEmptyOrNullFieldListEmbedsTheBodyAlone() {
        assertEquals("body", chunk("body", Map.of()).embedText(List.of()));
        assertEquals("body", chunk("body", Map.of()).embedText(null));
    }

    @Test
    void resolvesUriFromTheChunkAndAnythingElseFromMetadata() {
        assertTrue(chunk("body", Map.of()).embedText(List.of("uri")).contains("uri: file:///holidays.pdf"));
        assertTrue(chunk("body", Map.of("author", "ada")).embedText(List.of("author"))
                .contains("author: ada"));
    }

    /** Only the embedding input changes; BM25, snippets and the answer's grounding all use text(). */
    @Test
    void leavesTheStoredTextUntouched() {
        Chunk c = rows();

        c.embedText(List.of("title", "sheet"));

        assertEquals("17 Dussehra 20/10/2026 Tuesday", c.text());
    }

    @Test
    void survivesNullMetadata() {
        assertEquals("title: public_holidays_2026.pdf\n\nbody",
                chunk("body", null).embedText(List.of("title", "sheet")));
    }

    @Test
    void withEmbeddingPreservesEveryField() {
        Chunk c = rows().withEmbedding(new Embedding("m", 3, new float[] {1f, 2f, 3f}));

        assertEquals("ent_1_3", c.id());
        assertEquals(3, c.ordinal());
        assertEquals("public_holidays_2026.pdf", c.title());
        assertEquals("2026", c.metadata().get("sheet"));
        assertEquals(3, c.embedding().dim());
    }
}
