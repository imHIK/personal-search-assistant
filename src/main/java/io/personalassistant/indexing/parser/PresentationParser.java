package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/** Includes speaker notes, which are often where the substance is. */
@ApplicationScoped
public class PresentationParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation");

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
