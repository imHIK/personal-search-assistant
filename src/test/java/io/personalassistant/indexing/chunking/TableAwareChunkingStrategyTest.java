package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.ParsedContent.Block;
import io.personalassistant.domain.model.ParsedContent.BlockKind;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Row-aligned chunking, and the header repetition that is the point of it.
 *
 * <p>The failure being fixed: a table split naively gives chunk 0 the column headers and every later
 * chunk nothing but values. Those later chunks share no term with a query about the table's subject and
 * sit nowhere near it in embedding space, so they never reach the top-k — and an answer assembled from
 * the chunks that did rank looks complete while missing half the rows.
 */
class TableAwareChunkingStrategyTest {

    private final TableAwareChunkingStrategy strategy = new TableAwareChunkingStrategy();

    private static Entity entity() {
        return TestData.ingestedFile("ent_1", "kn_1", "holidays.xlsx", "/tmp/holidays.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private static ParsedContent table(int rows) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(BlockKind.HEADING, "Holidays 2026", "", 0));
        blocks.add(new Block(BlockKind.TABLE_HEADER, "S.N\tNAME OF HOLIDAY\tDATE\tDAY", "Holidays 2026", 1));
        for (int i = 1; i <= rows; i++) {
            blocks.add(new Block(BlockKind.TABLE_ROW,
                    i + "\tHoliday number " + i + "\t" + i + "/01/2026\tMonday", "Holidays 2026", i + 1));
        }
        StringBuilder text = new StringBuilder();
        blocks.forEach(b -> text.append(b.text()).append('\n'));
        return new ParsedContent(text.toString(), Map.of(), blocks);
    }

    private List<Chunk> chunk(ParsedContent parsed, int maxSize) {
        return strategy.chunk(entity(), SourceType.LOCAL_FS, parsed,
                new ChunkingSpec("table", maxSize, 0, List.of()));
    }

    @Test
    void repeatsTheHeaderRowInEveryChunk() {
        List<Chunk> chunks = chunk(table(120), 600);

        assertTrue(chunks.size() > 1, "the table must actually span several chunks to prove anything");
        for (Chunk c : chunks) {
            assertTrue(c.text().contains("NAME OF HOLIDAY"),
                    "chunk " + c.ordinal() + " has no header row, so nothing connects it to the table:\n"
                            + c.text());
            assertTrue(c.text().contains("Holidays 2026"),
                    "chunk " + c.ordinal() + " lost the heading that names the table");
        }
    }

    @Test
    void neverSplitsARowInHalf() {
        for (Chunk c : chunk(table(120), 600)) {
            for (String line : c.text().split("\n")) {
                if (line.startsWith("S.N") || line.equals("Holidays 2026") || line.isBlank()) {
                    continue;
                }
                assertTrue(line.matches("\\d+\t.*\t\\d+/01/2026\tMonday"),
                        "row was cut: \"" + line + "\"");
            }
        }
    }

    @Test
    void everyRowSurvivesExactlyOnce() {
        List<Chunk> chunks = chunk(table(50), 600);
        String all = chunks.stream().map(Chunk::text).reduce("", (a, b) -> a + "\n" + b);

        for (int i = 1; i <= 50; i++) {
            assertEquals(1, countOccurrences(all, "\t" + i + "/01/2026\t"),
                    "row " + i + " must appear exactly once across all chunks — no overlap duplicates "
                            + "it and no boundary drops it");
        }
    }

    /** With the header repeated, overlap would duplicate rows without adding recoverable context. */
    @Test
    void appliesNoRowOverlap() {
        List<Chunk> chunks = chunk(table(60), 600);
        String first = chunks.get(0).text();
        String second = chunks.get(1).text();

        List<String> firstRows = first.lines().filter(l -> l.matches("\\d+\t.*")).toList();
        List<String> secondRows = second.lines().filter(l -> l.matches("\\d+\t.*")).toList();
        assertTrue(firstRows.stream().noneMatch(secondRows::contains),
                "no row should appear in two consecutive chunks");
    }

    @Test
    void recordsTheRowRangeAndHeadingAsChunkFacets() {
        List<Chunk> chunks = chunk(table(60), 600);

        assertEquals("Holidays 2026", chunks.get(0).metadata().get("headingPath"));
        assertTrue(chunks.get(0).metadata().get("rowRange").toString().matches("\\d+-\\d+"),
                "rowRange locates the chunk inside the table: " + chunks.get(0).metadata());
        assertFalse(chunks.get(1).metadata().get("rowRange").toString()
                        .equals(chunks.get(0).metadata().get("rowRange").toString()),
                "consecutive chunks cover different rows");
    }

    /**
     * PDFs have no table semantics — a visual table arrives as separate paragraphs, not
     * {@code <table>} markup — so with no structural blocks this must behave like the recursive default
     * rather than produce nothing.
     */
    @Test
    void fallsBackToRecursiveSplittingWhenThereIsNoTableStructure() {
        ParsedContent prose = new ParsedContent("First paragraph.\n\nSecond paragraph.\n\nThird.",
                Map.of(), List.of());

        List<Chunk> chunks = chunk(prose, 1000);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("First paragraph."));
        assertTrue(chunks.get(0).text().contains("Third."));
    }

    /**
     * Rows are short and dense, so the prose default of 1000 characters holds very few of them and turns
     * one table into many chunks. Anything below the table default is widened to it; a larger explicit
     * size is honoured as-is.
     */
    @Test
    void widensProseSizedWindowsToTheTableDefault() {
        int atProseDefault = chunk(table(200), 1000).size();
        int wellBelow = chunk(table(200), 600).size();
        assertEquals(wellBelow, atProseDefault,
                "both are below the table default, so both are widened to it");
        assertEquals(chunk(table(200), TableAwareChunkingStrategy.DEFAULT_TABLE_SIZE).size(), atProseDefault);

        assertTrue(chunk(table(200), 8000).size() < atProseDefault,
                "an explicitly larger window is respected and yields fewer chunks");
    }

    @Test
    void prefersSpreadsheetContentTypesOnly() {
        assertTrue(strategy.prefers("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(strategy.prefers("text/csv; charset=UTF-8"), "MIME parameters must not defeat it");
        assertTrue(strategy.prefers("APPLICATION/VND.MS-EXCEL"), "matching is case-insensitive");
        assertFalse(strategy.prefers("application/pdf"), "a PDF has no table markup to work with");
        assertFalse(strategy.prefers("text/plain"));
        assertFalse(strategy.prefers(null));
    }

    @Test
    void isRegisteredUnderItsName() {
        assertEquals("table", strategy.name());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
