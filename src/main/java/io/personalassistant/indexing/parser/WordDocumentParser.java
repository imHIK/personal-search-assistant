package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/**
 * Dedicated Word extractor for both modern OOXML ({@code .docx}) and legacy OLE ({@code .doc}).
 * Runs POI through Tika with headers/footers included and phonetic runs de-duplicated, yielding
 * clean paragraph/heading/table text in reading order. Preferred over the generic fallback so Word
 * documents get Office-aware handling rather than best-effort sniffing.
 */
@ApplicationScoped
public class WordDocumentParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "application/msword",                                                        // .doc
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",   // .docx
            "application/vnd.oasis.opendocument.text");                                  // .odt

    private final ParseContext context = TikaSupport.officeContext();

    @Override
    public boolean supports(String contentType) {
        return TYPES.contains(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("word", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
