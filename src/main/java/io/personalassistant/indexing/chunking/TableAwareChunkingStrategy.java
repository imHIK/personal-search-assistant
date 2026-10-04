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
 * Chunks tables by row and repeats the header row atop every chunk, so each chunk is retrievable and readable
 * on its own; never splits mid-row. Falls back to recursive splitting when the parser reported no table
 * structure, as with most PDFs.
 */
@ApplicationScoped
public class TableAwareChunkingStrategy implements ChunkingStrategy {

    public static final String NAME = "table";

    private static final Set<String> PREFERRED_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv",
            "application/csv",
            "text/tab-separated-values");

    /** Rows are short and dense, so the prose default would fit very few of them per chunk. */
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

    /** No overlap: the repeated header already provides context, and a duplicated row would match twice. */
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
                    header = "";     // a new section means a new table and a new header row
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
                }
            }
        }
        flush(out, current, heading, header, firstRow, lastRow);
        return out;
    }

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
        // A chunk holding only the repeated prefix carries no data.
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

    private record Piece(String text, Map<String, Object> facets) {}
}
