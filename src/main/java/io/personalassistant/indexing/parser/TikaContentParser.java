package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import org.apache.tika.parser.ParseContext;

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

    @Override
    public boolean supports(String contentType) {
        return true; // fallback for anything the specific parsers don't claim
    }

    /**
     * Goes through {@link TikaSupport} like every other parser rather than calling
     * {@code Tika.parseToString}. That convenience method runs a plain-text handler internally, so this
     * path — the one an unrecognised MIME type lands on, which on macOS includes any {@code .xlsx} whose
     * type {@code Files.probeContentType} failed to identify — would otherwise be the single route that
     * still flattened tables into undelimited text. It gets no format-specific {@code ParseContext};
     * detection is by content sniffing, which is what this parser is for.
     */
    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("tika", input, contentType, new ParseContext());
    }

    @Override
    public int priority() {
        return 100;
    }
}
