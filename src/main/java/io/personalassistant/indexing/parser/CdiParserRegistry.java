package io.personalassistant.indexing.parser;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers all {@link ContentParser} beans via CDI and selects one by MIME type, honouring
 * {@link ContentParser#priority()} so a specific parser is tried before the general fallback.
 */
@ApplicationScoped
public class CdiParserRegistry implements ParserRegistry {

    private final List<ContentParser> parsers;

    @Inject
    public CdiParserRegistry(Instance<ContentParser> parsers) {
        this.parsers = parsers.stream()
                .sorted(Comparator.comparingInt(ContentParser::priority))
                .toList();
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
