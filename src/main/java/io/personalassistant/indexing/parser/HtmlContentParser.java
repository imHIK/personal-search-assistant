package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/** Claims {@code text/html}, which PlainTextParser deliberately leaves alone. */
@ApplicationScoped
public class HtmlContentParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "text/html",
            "application/xhtml+xml");

    private final ParseContext context = new ParseContext();

    @Override
    public boolean supports(String contentType) {
        return TYPES.contains(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("html", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
