package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.xml.sax.SAXException;

/**
 * Shared Tika plumbing for the per-type {@link ContentParser}s. Each dedicated parser claims its own
 * MIME types and supplies a tuned {@link ParseContext} (PDF layout config, Office notes/headers…);
 * the actual extraction — pick the concrete Tika parser, run it into a body handler, salvage on the
 * size cap, harvest a little metadata — is identical and lives here so the parsers stay one-liners.
 *
 * <p>A single {@link AutoDetectParser} is reused across calls: it routes the bytes to the right
 * concrete parser (PDFBox, POI, the HTML parser…) and honours whatever config the caller placed in
 * the {@link ParseContext}. Config travels on the context, not the parser, so sharing one is safe.
 */
final class TikaSupport {

    private TikaSupport() {
    }

    /** Safety cap on extracted characters; hitting it yields the text captured so far, not a failure. */
    static final int MAX_CHARS = 10_000_000;

    private static final Parser AUTO = new AutoDetectParser();

    /** Normalize a raw content type to a bare, lower-cased MIME (drops {@code ; charset=…} params). */
    static String baseType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    /** A parse context tuned for digital PDFs: reading-order text, no inline-image/OCR work. */
    static ParseContext pdfContext() {
        PDFParserConfig cfg = new PDFParserConfig();
        cfg.setSortByPosition(true);                  // reconstruct reading order for multi-column pages
        cfg.setSuppressDuplicateOverlappingText(true); // drop shadow/overlap artefacts
        cfg.setExtractInlineImages(false);            // no OCR path here — skip images entirely
        ParseContext ctx = new ParseContext();
        ctx.set(PDFParserConfig.class, cfg);
        return ctx;
    }

    /** A parse context for Office formats: keep slide notes and headers/footers, drop phonetic runs. */
    static ParseContext officeContext() {
        OfficeParserConfig cfg = new OfficeParserConfig();
        cfg.setIncludeSlideNotes(true);        // speaker notes are meaningful content for PPT
        cfg.setIncludeHeadersAndFooters(true);
        cfg.setConcatenatePhoneticRuns(false); // avoid duplicated CJK phonetic text
        ParseContext ctx = new ParseContext();
        ctx.set(OfficeParserConfig.class, cfg);
        return ctx;
    }

    /**
     * Run Tika into a {@link StructureAwareHandler} and return the extracted text, the structural blocks
     * behind it, and a little harvested metadata. Any parse error is surfaced so the entity's
     * retry/backoff path records it.
     *
     * <p>The handler, rather than {@code BodyContentHandler}, is the whole point: the plain-text handlers
     * discard Tika's XHTML markup, which is what turned a spreadsheet into an undelimited run of cell
     * values with no row boundaries for the chunker to split on. See {@link StructureAwareHandler}.
     *
     * <p>The {@link #MAX_CHARS} cap is enforced by the handler itself — it stops appending and keeps what
     * it has, so there is no write-limit exception to catch and partial extraction stays partial rather
     * than failing. (Tika's own limit is what the old {@code isWriteLimitReached} salvage existed for.)
     */
    static ParsedContent extract(String parserName, InputStream input, String contentType, ParseContext context) {
        StructureAwareHandler handler = new StructureAwareHandler(MAX_CHARS);
        Metadata metadata = new Metadata();
        String base = baseType(contentType);
        if (!base.isEmpty()) {
            metadata.set(Metadata.CONTENT_TYPE, base);
        }
        try {
            AUTO.parse(input, handler, metadata, context);
        } catch (IOException | SAXException | TikaException e) {
            if (!isWriteLimitReached(e)) {
                throw new IllegalStateException("Text extraction failed for " + base + " (" + parserName + ")", e);
            }
        }
        return new ParsedContent(handler.text(), harvest(parserName, base, metadata), handler.blocks());
    }

    /** True if the cause chain includes Tika's write-limit signal (matched by name to stay version-agnostic). */
    private static boolean isWriteLimitReached(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("WriteLimitReachedException")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> harvest(String parserName, String base, Metadata md) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parser", parserName);
        if (!base.isEmpty()) {
            out.put("detectedType", base);
        }
        putIfPresent(out, "docTitle", md.get(TikaCoreProperties.TITLE));
        putIfPresent(out, "docAuthor", md.get(TikaCoreProperties.CREATOR));
        putIfPresent(out, "pageCount", md.get("xmpTPg:NPages")); // set by the PDF + Office parsers
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }
}
