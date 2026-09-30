package io.personalassistant.publishing.email;

/**
 * @param subject single-line and header-safe
 * @param text the plain-text alternative, always present
 */
public record RenderedEmail(String subject, String html, String text) {
}
