package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import org.apache.tika.parser.ParseContext;

/**
 * Dedicated PDF extractor. Uses PDFBox (via Tika) tuned for <em>digital</em> PDFs: text is
 * re-ordered by position so multi-column pages read top-to-bottom-left-to-right, overlapping/shadow
 * text is de-duplicated, and inline images are skipped (there is no OCR path here — scanned PDFs
 * would need the deferred OCR parser). Preferred over the generic fallback for {@code application/pdf}.
 */
@ApplicationScoped
public class PdfContentParser implements ContentParser {

    private final ParseContext context = TikaSupport.pdfContext();

    @Override
    public boolean supports(String contentType) {
        return "application/pdf".equals(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("pdf", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
