package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

/**
 * General-purpose extractor backed by Apache Tika: handles PDF, DOCX, HTML, RTF, ODF and many
 * more by sniffing the bytes. Registered as the fallback (high {@link #priority()}) so the
 * cheaper {@link PlainTextParser} wins for plain-text types. An OCR variant for scanned documents
 * can be added later as a separate, higher-priority parser without touching callers.
 */
@ApplicationScoped
public class TikaContentParser implements ContentParser {

    /** Generous cap on extracted characters; bump if very large documents must be indexed whole. */
    private static final int MAX_CHARS = 10_000_000;

    private final Tika tika = newTika();

    private static Tika newTika() {
        Tika t = new Tika();
        t.setMaxStringLength(MAX_CHARS);
        return t;
    }

    @Override
    public boolean supports(String contentType) {
        return true; // fallback for anything the specific parsers don't claim
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        try {
            String text = tika.parseToString(input);
            return new ParsedContent(text, Map.of("parser", "tika", "detectedType",
                    contentType == null ? "unknown" : contentType));
        } catch (IOException | TikaException e) {
            throw new IllegalStateException("Tika extraction failed for type " + contentType, e);
        }
    }

    @Override
    public int priority() {
        return 100;
    }
}
