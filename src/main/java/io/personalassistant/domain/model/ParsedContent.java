package io.personalassistant.domain.model;

import java.util.List;
import java.util.Map;

/** @param blocks structural units in document order; empty when the format has none */
public record ParsedContent(String text, Map<String, Object> metadata, List<Block> blocks) {

    public ParsedContent(String text, Map<String, Object> metadata) {
        this(text, metadata, List.of());
    }

    public ParsedContent {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public enum BlockKind {
        /** A section title, sheet name or slide title. */
        HEADING,
        /** A paragraph or list item. */
        PARAGRAPH,
        /** Lets a chunker repeat the header into every chunk of the table. */
        TABLE_HEADER,
        TABLE_ROW
    }

    /**
     * @param locator the enclosing heading or sheet name, or {@code "page"}/{@code "slide"}; empty when
     *                nothing locates it
     * @param row 1-based row within the current table, or 0
     */
    public record Block(BlockKind kind, String text, String locator, int row) {

        public boolean isTable() {
            return kind == BlockKind.TABLE_HEADER || kind == BlockKind.TABLE_ROW;
        }
    }
}
