package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Reads {@code text/*} as UTF-8, except HTML, which needs its markup stripped. */
@ApplicationScoped
public class PlainTextParser implements ContentParser {

    @Override
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = TikaSupport.baseType(contentType);
        if (ct.equals("text/html") || ct.equals("application/xhtml+xml")) {
            return false;
        }
        return ct.startsWith("text/")
                || ct.equals("application/json")
                || ct.equals("application/xml")
                || ct.equals("application/x-yaml")
                || ct.equals("application/yaml")
                || ct.equals("application/csv")
                || ct.equals("text/csv")
                || ct.equals("text/tab-separated-values");
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        try {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return new ParsedContent(text, Map.of("parser", "plain-text"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read text stream", e);
        }
    }

    @Override
    public int priority() {
        return 0;
    }
}
