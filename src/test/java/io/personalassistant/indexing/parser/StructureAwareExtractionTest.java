package io.personalassistant.indexing.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.ParsedContent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.Test;

/**
 * The extraction half of L3. A table has to arrive with its rows on separate lines and its cells
 * separated, or the recursive chunker has no {@code \n} to split on, falls to the {@code " "} rung of its
 * separator ladder, and hard-windows mid-row — which is what made a chunk of a holiday list start and end
 * mid-date.
 *
 * <p>HTML is used as the fixture because it exercises exactly the SAX events every Tika parser emits for
 * a table ({@code <table>/<tr>/<td>/<th>}), with no binary fixture to check in.
 */
class StructureAwareExtractionTest {

    private static ParsedContent parse(String html) {
        return TikaSupport.extract("test", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                "text/html", new ParseContext());
    }

    @Test
    void emitsOneLinePerTableRowWithSeparatedCells() {
        ParsedContent parsed = parse("""
                <html><body><h1>Holidays 2026</h1><table>
                <tr><th>Holiday</th><th>Date</th><th>Day</th></tr>
                <tr><td>Republic Day</td><td>26/01/2026</td><td>Monday</td></tr>
                <tr><td>Holi</td><td>03/03/2026</td><td>Tuesday</td></tr>
                </table></body></html>
                """);

        List<String> lines = parsed.text().lines().filter(l -> !l.isBlank()).toList();
        assertTrue(lines.contains("Holiday\tDate\tDay"), "header row: " + lines);
        assertTrue(lines.contains("Republic Day\t26/01/2026\tMonday"), "data row: " + lines);
        assertTrue(lines.contains("Holi\t03/03/2026\tTuesday"), lines.toString());
        assertFalse(parsed.text().contains("MondayHoli"),
                "rows must not run together — that is the bug: " + parsed.text());
    }

    @Test
    void recordsTheHeaderRowSeparatelySoAChunkerCanRepeatIt() {
        List<ParsedContent.Block> blocks = parse("""
                <html><body><table>
                <tr><th>Holiday</th><th>Date</th></tr>
                <tr><td>Republic Day</td><td>26/01/2026</td></tr>
                </table></body></html>
                """).blocks();

        List<ParsedContent.Block> headers = blocks.stream()
                .filter(b -> b.kind() == ParsedContent.BlockKind.TABLE_HEADER).toList();
        assertEquals(1, headers.size(), "exactly one header row: " + blocks);
        assertEquals("Holiday\tDate", headers.get(0).text());
        assertTrue(blocks.stream().anyMatch(b -> b.kind() == ParsedContent.BlockKind.TABLE_ROW));
    }

    /**
     * POI's Excel path emits {@code <td>} for the header row rather than {@code <th>}, so treating the
     * first row of a table as its header is what gives a spreadsheet a header to repeat at all.
     */
    @Test
    void treatsTheFirstRowAsTheHeaderEvenWithoutThTags() {
        List<ParsedContent.Block> blocks = parse("""
                <html><body><table>
                <tr><td>Holiday</td><td>Date</td></tr>
                <tr><td>Republic Day</td><td>26/01/2026</td></tr>
                <tr><td>Holi</td><td>03/03/2026</td></tr>
                </table></body></html>
                """).blocks();

        assertEquals(ParsedContent.BlockKind.TABLE_HEADER,
                blocks.stream().filter(ParsedContent.Block::isTable).findFirst().orElseThrow().kind());
        assertEquals(2, blocks.stream()
                .filter(b -> b.kind() == ParsedContent.BlockKind.TABLE_ROW).count(),
                "only the first row is the header: " + blocks);
    }

    @Test
    void keepsHeadingsAsTheirOwnBlockAndLocatesRowsUnderThem() {
        List<ParsedContent.Block> blocks = parse("""
                <html><body><h1>Sheet1</h1><table>
                <tr><td>a</td><td>b</td></tr>
                <tr><td>1</td><td>2</td></tr>
                </table></body></html>
                """).blocks();

        assertTrue(blocks.stream().anyMatch(b -> b.kind() == ParsedContent.BlockKind.HEADING
                && b.text().equals("Sheet1")), blocks.toString());
        assertTrue(blocks.stream().filter(ParsedContent.Block::isTable)
                .allMatch(b -> b.locator().equals("Sheet1")),
                "rows carry the heading that locates them: " + blocks);
    }

    @Test
    void numbersRowsWithinTheirTable() {
        List<ParsedContent.Block> rows = parse("""
                <html><body><table>
                <tr><td>h</td></tr><tr><td>one</td></tr><tr><td>two</td></tr>
                </table></body></html>
                """).blocks().stream().filter(b -> b.kind() == ParsedContent.BlockKind.TABLE_ROW).toList();

        assertEquals(2, rows.get(0).row(), "the header is row 1");
        assertEquals(3, rows.get(1).row());
    }

    @Test
    void stillExtractsOrdinaryProse() {
        ParsedContent parsed = parse("<html><body><p>First para.</p><p>Second para.</p></body></html>");

        assertTrue(parsed.text().contains("First para."));
        assertTrue(parsed.text().contains("Second para."));
        assertEquals(2, parsed.blocks().stream()
                .filter(b -> b.kind() == ParsedContent.BlockKind.PARAGRAPH).count(),
                "paragraphs stay separate blocks: " + parsed.blocks());
    }

    @Test
    void dropsEntirelyEmptyRowsRatherThanEmittingBlankLines() {
        ParsedContent parsed = parse("""
                <html><body><table>
                <tr><td>a</td></tr><tr><td></td><td></td></tr><tr><td>b</td></tr>
                </table></body></html>
                """);

        assertFalse(parsed.text().contains("\n\n\n"), "no run of blank lines: " + parsed.text());
        assertEquals(1, parsed.blocks().stream()
                .filter(b -> b.kind() == ParsedContent.BlockKind.TABLE_ROW).count());
    }

    @Test
    void harvestsMetadataAsBefore() {
        assertEquals("test", parse("<html><body><p>x</p></body></html>").metadata().get("parser"));
    }
}
