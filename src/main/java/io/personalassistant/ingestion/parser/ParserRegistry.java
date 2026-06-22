package io.personalassistant.ingestion.parser;

/** Picks a {@link ContentParser} for a MIME type. New parsers register themselves. */
public interface ParserRegistry {

    /** @throws IllegalArgumentException if no parser supports the type */
    ContentParser get(String contentType);

    boolean supports(String contentType);
}
