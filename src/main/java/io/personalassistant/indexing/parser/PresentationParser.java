package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/**
 * Dedicated slide-deck extractor for PowerPoint ({@code .ppt}/{@code .pptx}) and ODF presentations.
 * Uses the Office config that <em>includes speaker notes</em>, so both the on-slide text and the
 * presenter notes are indexed — the notes are often where the real substance lives. Preferred over
 * the generic fallback for presentation types.
 */
@ApplicationScoped
public class PresentationParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "application/vnd.ms-powerpoint",                                                   // .ppt
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",       // .pptx
            "application/vnd.oasis.opendocument.presentation");                                // .odp

    private final ParseContext context = TikaSupport.officeContext();

    @Override
    public boolean supports(String contentType) {
        return TYPES.contains(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("presentation", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
