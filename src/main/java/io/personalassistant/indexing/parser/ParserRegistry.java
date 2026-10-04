package io.personalassistant.indexing.parser;

public interface ParserRegistry {

    /** @throws IllegalArgumentException if no parser supports the type */
    ContentParser get(String contentType);

    boolean supports(String contentType);
}
