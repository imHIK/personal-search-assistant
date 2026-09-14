package io.personalassistant.publishing.email;

/**
 * A message rendered for email.
 *
 * @param subject single-line subject, header-safe
 * @param html    the HTML body
 * @param text    the plain-text alternative — always present, for clients and filters that ignore HTML
 */
public record RenderedEmail(String subject, String html, String text) {
}
