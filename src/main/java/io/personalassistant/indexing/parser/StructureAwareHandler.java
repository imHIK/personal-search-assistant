package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Keeps the structure Tika reports as XHTML, which the plain-text handlers drop: rows end in newlines, cells
 * are tab-separated, headings and page or slide breaks get blank lines, so chunkers split on real boundaries.
 * Also records ParsedContent blocks. One instance per document; not thread-safe.
 */
final class StructureAwareHandler extends DefaultHandler {

    private static final String CELL_SEPARATOR = "\t";

    /** Only the block list stops at this cap; text extraction continues. */
    private static final int MAX_BLOCKS = 100_000;

    private final StringBuilder text = new StringBuilder();
    private final List<ParsedContent.Block> blocks = new ArrayList<>();

    private final StringBuilder inline = new StringBuilder();

    private final StringBuilder cell = new StringBuilder();

    private final StringBuilder headingText = new StringBuilder();

    private final List<String> row = new ArrayList<>();

    private final int maxChars;

    private int cellDepth;
    private int headingDepth;
    private boolean headerRowPending;
    private boolean rowIsHeader;
    private int rowNumber;
    /** A sheet name arrives as a heading. */
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
                // The first row is the header even as <td>: POI's Excel path never emits <th>.
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
                // Tika marks PDF pages and PPT slides as <div class="page"> and <div class="slide">.
                String cls = attributes.getValue("class");
                if ("page".equals(cls) || "slide".equals(cls)) {
                    flushInline();
                    blankLine();
                    pageLocator = cls;
                }
            }
            case "p", "li", "br" -> flushInline();
            default -> {
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
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        // Loose text still lands in inline, so nothing is dropped: every path that would clobber a
        // buffer flushes it first.
        StringBuilder target = cellDepth > 0 ? cell : headingDepth > 0 ? headingText : inline;
        target.append(ch, start, length);
    }

    @Override
    public void endDocument() {
        endRow();       // an unclosed final row still carries content
        flushInline();
    }

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
            return;
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
