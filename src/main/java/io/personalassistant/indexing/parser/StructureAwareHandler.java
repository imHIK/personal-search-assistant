package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A Tika content handler that keeps the document structure Tika already reports and the plain-text
 * handlers throw away.
 *
 * <p><strong>Why this exists.</strong> Tika's parsers emit XHTML — a spreadsheet becomes
 * {@code <table><tr><td>cell</td>…}, a PDF page becomes {@code <div class="page">}, a heading becomes
 * {@code <h2>}. {@code ToTextContentHandler} (which is what {@code BodyContentHandler} wraps) keeps only
 * the character data and drops every tag, so a spreadsheet arrives as an <em>undelimited run of cell
 * values</em>: no newline between rows, no separator between cells. The recursive chunker then finds no
 * {@code \n\n} and no {@code \n}, falls to the {@code " "} rung of its separator ladder, and hard-windows
 * at the character limit — cutting mid-row and mid-value. This is the extraction half of L3.
 *
 * <p>So the tags become text: a row ends with a newline, cells are tab-separated, headings and page or
 * slide boundaries get blank lines. That alone makes the existing recursive strategy split at row
 * boundaries with no other change.
 *
 * <p>It also records a parallel {@link ParsedContent.Block} list, so a structure-aware
 * {@code ChunkingStrategy} can split on real boundaries — and repeat a table's header row into every
 * chunk — rather than pattern-matching a flat string. Both outputs come from one pass; a caller that only
 * wants text ignores the blocks.
 *
 * <p>Not thread-safe and not reusable: one instance parses one document, which is how Tika content
 * handlers are used.
 */
final class StructureAwareHandler extends DefaultHandler {

    /** Separator between cells of one row. A tab keeps the row on one line and the columns visible. */
    private static final String CELL_SEPARATOR = "\t";

    /** Ceiling on recorded blocks. Text is already capped; this stops a pathological document's
     * structure list from outgrowing it. Text extraction continues after the cap — only blocks stop. */
    private static final int MAX_BLOCKS = 100_000;

    private final StringBuilder text = new StringBuilder();
    private final List<ParsedContent.Block> blocks = new ArrayList<>();

    /** Characters of the block-level element currently open (paragraph, list item, heading). */
    private final StringBuilder inline = new StringBuilder();

    /** Characters of the table cell currently open. */
    private final StringBuilder cell = new StringBuilder();

    /** Characters of the heading currently open. */
    private final StringBuilder headingText = new StringBuilder();

    /** Cells accumulated for the row currently open. */
    private final List<String> row = new ArrayList<>();

    private final int maxChars;

    private int cellDepth;
    private int headingDepth;
    /** True until a table's header row has been emitted; the first row is the header. */
    private boolean headerRowPending;
    private boolean rowIsHeader;
    private int rowNumber;
    /** Most recent heading, used to locate blocks (a sheet name arrives as a heading). */
    private String heading = "";
    private String pageLocator = "";

    StructureAwareHandler(int maxChars) {
        this.maxChars = maxChars;
    }

    String text() {
        return text.toString();
    }

    List<ParsedContent.Block> blocks() {
        return List.copyOf(blocks);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        switch (tag(localName, qName)) {
            case "table" -> {
                flushInline();
                // The first row of a table is its header even when the parser emits <td> rather than
                // <th> — POI's Excel path does exactly that, so relying on <th> alone would leave a
                // spreadsheet with no header row to repeat.
                headerRowPending = true;
                rowNumber = 0;
                blankLine();
            }
            case "tr" -> {
                flushInline();
                row.clear();
                rowIsHeader = headerRowPending;
                rowNumber++;
            }
            case "td" -> {
                flushInline();
                cellDepth++;
                cell.setLength(0);
            }
            case "th" -> {
                flushInline();
                cellDepth++;
                rowIsHeader = true;
                cell.setLength(0);
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flushInline();
                headingDepth++;
                headingText.setLength(0);
            }
            case "div" -> {
                // Tika marks PDF pages and PPT slides as <div class="page"> / <div class="slide">.
                String cls = attributes.getValue("class");
                if ("page".equals(cls) || "slide".equals(cls)) {
                    flushInline();
                    blankLine();
                    pageLocator = cls;
                }
            }
            case "p", "li", "br" -> flushInline();
            default -> {
                // Everything else contributes only its characters.
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        switch (tag(localName, qName)) {
            case "td", "th" -> {
                cellDepth = Math.max(0, cellDepth - 1);
                row.add(collapse(cell.toString()));
                cell.setLength(0);
            }
            case "tr" -> endRow();
            case "table" -> {
                headerRowPending = false;
                blankLine();
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                headingDepth = Math.max(0, headingDepth - 1);
                endHeading();
            }
            case "p", "li" -> flushInline();
            default -> {
                // No structural meaning.
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        // Loose text outside any element we track still lands in `inline`, so nothing is dropped:
        // every path that would clobber a buffer flushes it first.
        StringBuilder target = cellDepth > 0 ? cell : headingDepth > 0 ? headingText : inline;
        target.append(ch, start, length);
    }

    @Override
    public void endDocument() {
        endRow();       // an unclosed final row still carries content
        flushInline();
    }

    /** Emit whatever loose/paragraph text has accumulated as its own paragraph, then reset. */
    private void flushInline() {
        String body = collapse(inline.toString());
        inline.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        append(body);
        append("\n\n");
        record(ParsedContent.BlockKind.PARAGRAPH, body, 0);
    }

    private void endRow() {
        if (row.isEmpty()) {
            return;
        }
        String line = String.join(CELL_SEPARATOR, row).strip();
        boolean header = rowIsHeader;
        int number = rowNumber;
        row.clear();
        rowIsHeader = false;
        if (line.isEmpty()) {
            return;   // an all-empty row carries nothing; emitting it would only add a blank line
        }
        append(line);
        append("\n");
        record(header ? ParsedContent.BlockKind.TABLE_HEADER : ParsedContent.BlockKind.TABLE_ROW,
                line, number);
        if (header) {
            headerRowPending = false;
        }
    }

    private void endHeading() {
        String value = collapse(headingText.toString());
        headingText.setLength(0);
        if (value.isEmpty()) {
            return;
        }
        blankLine();
        append(value);
        append("\n\n");
        heading = value;
        record(ParsedContent.BlockKind.HEADING, value, 0);
    }

    /** A blank line between structural units, without stacking up runs of them. */
    private void blankLine() {
        if (text.isEmpty()) {
            return;
        }
        while (text.length() > 0 && text.charAt(text.length() - 1) == '\n') {
            text.setLength(text.length() - 1);
        }
        append("\n\n");
    }

    private void record(ParsedContent.BlockKind kind, String content, int rowNo) {
        if (blocks.size() >= MAX_BLOCKS) {
            return;
        }
        blocks.add(new ParsedContent.Block(kind, content, locator(), rowNo));
    }

    private String locator() {
        return heading.isEmpty() ? pageLocator : heading;
    }

    /** Collapse whitespace runs: cell and heading text is display text, not preformatted. */
    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private void append(String value) {
        if (text.length() >= maxChars) {
            return;
        }
        int room = maxChars - text.length();
        text.append(value, 0, Math.min(value.length(), room));
    }

    private static String tag(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        if (name == null) {
            return "";
        }
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
    }
}
