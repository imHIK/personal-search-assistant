package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chunks tabular documents by row, repeating the table's header row at the top of every chunk.
 *
 * <p><strong>The header repetition is the point.</strong> A table split naively gives its first chunk the
 * column headers and every later chunk nothing but bare values. Those later chunks are then close to
 * unretrievable: a chunk holding {@code "17 Dussehra 20/10/2026 Tuesday"} shares no term with a query
 * about holidays and sits nowhere near it in embedding space, so it never reaches the top-k — and an
 * answer built from the chunks that did rank looks complete while missing half the table. Repeating
 * {@code "S.N NAME OF HOLIDAY DATE DAY"} into every chunk makes each one independently retrievable by
 * both the lexical and the vector leg, and independently interpretable by the model reading it.
 *
 * <p>It also never splits mid-row, so a date or a cell value can no longer be cut in half.
 *
 * <p>Falls back to recursive character splitting when the parser reported no table structure — which is
 * the common case for PDFs, where the format has no table semantics and a visual table arrives as a
 * sequence of paragraphs. This strategy helps formats that carry real table markup (spreadsheets, Word
 * tables, HTML); for the rest the win has to come from chunk sizing and from the embedded context
 * prefix, not from here.
 */
@ApplicationScoped
public class TableAwareChunkingStrategy implements ChunkingStrategy {

    public static final String NAME = "table";

    /** Content types whose parsers emit real table markup, so row-aware chunking has something to use. */
    private static final Set<String> PREFERRED_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv",
            "application/csv",
            "text/tab-separated-values");

    /**
     * Tabular rows are short and dense, so the prose default of 1000 characters holds very few of them and
     * multiplies the number of chunks a table becomes. This raises the working size when the caller has
     * not asked for something specific.
     */
    static final int DEFAULT_TABLE_SIZE = 2000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean prefers(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String base = (semi >= 0 ? contentType.substring(0, semi) : contentType)
                .trim().toLowerCase(Locale.ROOT);
        return PREFERRED_TYPES.contains(base);
    }

    /** With no structural blocks there is nothing table-aware to do; behave like the recursive default. */
    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        return ChunkSupport.toChunks(entity, sourceType,
                TextSplitters.recursiveSplit(text, RecursiveCharacterChunkingStrategy.DEFAULT_SEPARATORS,
                        spec.maxSize(), spec.overlap()));
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, ParsedContent parsed, ChunkingSpec spec) {
        List<ParsedContent.Block> blocks = parsed.blocks();
        if (blocks.isEmpty() || blocks.stream().noneMatch(ParsedContent.Block::isTable)) {
            return chunk(entity, sourceType, parsed.text(), spec);
        }
        int maxSize = Math.max(spec.maxSize(), DEFAULT_TABLE_SIZE);
        List<Piece> pieces = pack(blocks, maxSize);
        return ChunkSupport.toChunks(entity, sourceType,
                pieces.stream().map(Piece::text).toList(),
                pieces.stream().map(Piece::facets).toList());
    }

    /**
     * Pack blocks into chunks, keeping every row whole and re-emitting the current header (and the
     * heading that locates it) whenever a new chunk starts. No overlap is applied: with the header
     * repeated, the redundancy overlap exists to provide is already there, and duplicating rows would
     * make the same row match twice.
     */
    private List<Piece> pack(List<ParsedContent.Block> blocks, int maxSize) {
        List<Piece> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String header = "";
        String heading = "";
        int firstRow = 0;
        int lastRow = 0;

        for (ParsedContent.Block block : blocks) {
            switch (block.kind()) {
                case HEADING -> {
                    flush(out, current, heading, header, firstRow, lastRow);
                    heading = block.text();
                    header = "";     // a new section means a new table, and a new header row
                    firstRow = 0;
                    lastRow = 0;
                }
                case TABLE_HEADER -> {
                    flush(out, current, heading, header, firstRow, lastRow);
                    header = block.text();
                    firstRow = 0;
                    lastRow = 0;
                }
                case TABLE_ROW, PARAGRAPH -> {
                    String line = block.text();
                    if (line.isBlank()) {
                        continue;
                    }
                    if (current.isEmpty()) {
                        current.append(prefix(heading, header));
                        firstRow = block.row();
                    }
                    if (current.length() + line.length() + 1 > maxSize && current.length() > prefix(heading, header).length()) {
                        flush(out, current, heading, header, firstRow, lastRow);
                        current.append(prefix(heading, header));
                        firstRow = block.row();
                    }
                    current.append(line).append('\n');
                    if (block.row() > 0) {
                        lastRow = block.row();
                    }
                }
                default -> {
                    // No other kinds exist today; a new one falls through as content-free.
                }
            }
        }
        flush(out, current, heading, header, firstRow, lastRow);
        return out;
    }

    /** The repeated context every chunk of a table opens with. */
    private String prefix(String heading, String header) {
        StringBuilder out = new StringBuilder();
        if (!heading.isEmpty()) {
            out.append(heading).append('\n');
        }
        if (!header.isEmpty()) {
            out.append(header).append('\n');
        }
        return out.toString();
    }

    private void flush(List<Piece> out, StringBuilder current, String heading, String header,
                       int firstRow, int lastRow) {
        String body = current.toString();
        current.setLength(0);
        // A chunk holding only the repeated prefix carries no data; emitting it would waste an ordinal.
        if (body.isBlank() || body.equals(prefix(heading, header))) {
            return;
        }
        out.add(new Piece(body.stripTrailing(), facets(heading, firstRow, lastRow)));
    }

    private Map<String, Object> facets(String heading, int firstRow, int lastRow) {
        Map<String, Object> facets = new LinkedHashMap<>();
        if (!heading.isEmpty()) {
            facets.put("headingPath", heading);
        }
        if (firstRow > 0 && lastRow >= firstRow) {
            facets.put("rowRange", firstRow + "-" + lastRow);
        }
        return facets;
    }

    /** One packed chunk: its text plus the chunk-level facets describing where it came from. */
    private record Piece(String text, Map<String, Object> facets) {}
}
