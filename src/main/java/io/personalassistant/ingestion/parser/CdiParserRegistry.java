package io.personalassistant.ingestion.parser;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Discovers all {@link ContentParser} beans via CDI and selects one by MIME type. Empty
 * today (no parsers yet).
 */
@ApplicationScoped
public class CdiParserRegistry implements ParserRegistry {

    private final List<ContentParser> parsers;

    @Inject
    public CdiParserRegistry(Instance<ContentParser> parsers) {
        this.parsers = parsers.stream().toList();
    }

    @Override
    public ContentParser get(String contentType) {
        return parsers.stream()
                .filter(p -> p.supports(contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No parser for content type " + contentType));
    }

    @Override
    public boolean supports(String contentType) {
        return parsers.stream().anyMatch(p -> p.supports(contentType));
    }
}
