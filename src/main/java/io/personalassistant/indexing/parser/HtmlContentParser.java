package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/**
 * Dedicated HTML/XHTML extractor. Tika's HTML parser drops {@code <script>}/{@code <style>} and
 * markup, leaving the visible text in document order — far more meaningful than indexing raw HTML
 * source as plain text. Claims {@code text/html} explicitly (the {@link PlainTextParser} deliberately
 * excludes it) so this parser wins for web pages and HTML email bodies.
 */
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
