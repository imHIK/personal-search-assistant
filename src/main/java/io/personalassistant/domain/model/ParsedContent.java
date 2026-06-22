package io.personalassistant.domain.model;

import java.util.Map;

/**
 * Output of a {@code ContentParser}: extracted plain text plus any metadata the
 * parser discovered (page count, author, embedded properties…).
 */
public record ParsedContent(String text, Map<String, Object> metadata) {}
