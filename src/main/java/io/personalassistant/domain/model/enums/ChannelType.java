package io.personalassistant.domain.model.enums;

/**
 * Where a published message goes. Each value corresponds to at most one {@code Publisher} bean.
 *
 * <p>Deliberately separate from {@link SourceType}: a source is something we <em>read</em>, a channel
 * is somewhere we <em>write</em>, and the two share no lifecycle, credentials or SPI. Constants may be
 * declared ahead of their publisher, the same convention as {@code SourceType.SLACK} — creating a
 * channel of a type with no bean is a 400, not a crash.
 */
public enum ChannelType {
    EMAIL,
    SLACK,
    WHATSAPP
}
