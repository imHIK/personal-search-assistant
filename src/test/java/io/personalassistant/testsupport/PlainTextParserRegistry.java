package io.personalassistant.testsupport;

import io.personalassistant.indexing.parser.ContentParser;
import io.personalassistant.indexing.parser.ParserRegistry;
import io.personalassistant.indexing.parser.PlainTextParser;

/** Parser registry that always returns a {@link PlainTextParser} (tests use text files). */
public class PlainTextParserRegistry implements ParserRegistry {

    private final ContentParser parser = new PlainTextParser();

    @Override
    public ContentParser get(String contentType) {
        return parser;
    }

    @Override
    public boolean supports(String contentType) {
        return true;
    }
}
