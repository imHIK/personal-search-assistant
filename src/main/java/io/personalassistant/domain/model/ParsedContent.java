package io.personalassistant.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Output of a {@code ContentParser}: extracted plain text, any metadata the parser discovered (page
 * count, author, embedded properties…), and — where the format has structure worth keeping — the
 * structural {@link Block}s that text was built from.
 *
 * <p>{@code text} remains the primary output and every parser produces it; {@code blocks} is additive.
 * A parser with nothing structural to report returns an empty list and behaves exactly as before, and a
 * chunking strategy that does not care about structure never looks at it.
 *
 * @param text     the extracted plain text
 * @param metadata parser-discovered document properties
 * @param blocks   structural units in document order, or empty when the format or parser has none
 */
public record ParsedContent(String text, Map<String, Object> metadata, List<Block> blocks) {

    public ParsedContent(String text, Map<String, Object> metadata) {
        this(text, metadata, List.of());
    }

    public ParsedContent {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * What a structural unit is. Deliberately coarse: these are the boundaries a chunking strategy can
     * act on, not a document model. Anything finer would have to be maintained per format for no gain.
     */
    public enum BlockKind {
        /** A section title, sheet name, or slide title. */
        HEADING,
        /** Running prose — a paragraph or list item. */
        PARAGRAPH,
        /** A table's column-header row. What lets a chunker repeat it into every chunk of the table. */
        TABLE_HEADER,
        /** One data row of a table, cells already separated. */
        TABLE_ROW
    }

    /**
     * One structural unit of a document.
     *
     * @param kind    what this unit is
     * @param text    its text, whitespace-collapsed, with table cells already separated
     * @param locator where it sits — the enclosing heading or sheet name, or {@code "page"}/{@code
     *                "slide"} when that is all the format reports. Empty when nothing locates it
     * @param row     1-based row number within the current table, or 0 when not a table row
     */
    public record Block(BlockKind kind, String text, String locator, int row) {

        public boolean isTable() {
            return kind == BlockKind.TABLE_HEADER || kind == BlockKind.TABLE_ROW;
        }
    }
}
