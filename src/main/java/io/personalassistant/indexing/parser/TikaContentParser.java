package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

/**
 * General-purpose extractor backed by Apache Tika, kept as the <strong>long-tail fallback</strong>
 * (highest {@link #priority()}) for content types no dedicated parser claims — RTF, EPUB, e-mail
 * containers, odd/unknown binaries — by sniffing the bytes. The common document families each have a
 * dedicated, better-tuned parser now ({@link PdfContentParser}, {@link WordDocumentParser},
 * {@link PresentationParser}, {@link SpreadsheetContentParser}, {@link HtmlContentParser},
 * {@link PlainTextParser}); this catches everything else so extraction never hard-fails on an
 * unexpected type. An OCR variant for scanned documents can slot in later as a higher-priority parser
 * without touching callers.
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
