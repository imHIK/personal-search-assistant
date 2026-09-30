package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

@ApplicationScoped
public class WordDocumentParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text");

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
