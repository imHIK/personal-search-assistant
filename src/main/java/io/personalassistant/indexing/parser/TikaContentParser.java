package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import org.apache.tika.parser.ParseContext;

/** The long-tail fallback (highest priority) for types no dedicated parser claims. */
@ApplicationScoped
public class TikaContentParser implements ContentParser {

    @Override
    public boolean supports(String contentType) {
        return true;
    }

    /**
     * Through TikaSupport rather than {@code Tika.parseToString}, whose plain-text handler would flatten
     * tables.
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
