package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles plain-text family content ({@code text/*}, JSON, CSV, source code) by reading the
 * stream as UTF-8. Preferred over the Tika fallback for these types (lower priority value).
 */
@ApplicationScoped
public class PlainTextParser implements ContentParser {

    @Override
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.startsWith("text/")
                || ct.equals("application/json")
                || ct.equals("application/xml")
                || ct.equals("application/x-yaml")
                || ct.equals("application/csv");
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
