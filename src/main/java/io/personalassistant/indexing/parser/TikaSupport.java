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

/** One AutoDetectParser is shared: config travels on the ParseContext, not the parser. */
final class TikaSupport {

    private TikaSupport() {
    }

    /** Hitting it yields the text captured so far, not a failure. */
    static final int MAX_CHARS = 10_000_000;

    private static final Parser AUTO = new AutoDetectParser();

    static String baseType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    static ParseContext pdfContext() {
        PDFParserConfig cfg = new PDFParserConfig();
        cfg.setSortByPosition(true);                  // reconstruct reading order for multi-column pages
        cfg.setSuppressDuplicateOverlappingText(true); // drop shadow/overlap artefacts
        cfg.setExtractInlineImages(false);            // no OCR path here — skip images entirely
        ParseContext ctx = new ParseContext();
        ctx.set(PDFParserConfig.class, cfg);
        return ctx;
    }

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
     * StructureAwareHandler, not BodyContentHandler, which discards the markup that delimits rows. The
     * handler enforces {@link #MAX_CHARS} itself, so a partial extraction stays partial.
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

    /** Matched by name to stay version-agnostic. */
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
        putIfPresent(out, "pageCount", md.get("xmpTPg:NPages"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }
}
