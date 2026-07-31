package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import java.io.InputStream;

/**
 * Extracts plain text (and incidental metadata) from raw bytes. One implementation per family of
 * content types (plain text, and a general extractor for PDF/DOCX/HTML/… via Apache Tika).
 * Selected by MIME type through the {@link ParserRegistry}; lower {@link #priority()} wins so a
 * specific parser is preferred over the general fallback.
 */
public interface ContentParser {

    /** @return true if this parser can handle the given MIME type */
    boolean supports(String contentType);

    /** Parse the stream's bytes into normalized text + metadata. The caller owns the stream. */
    ParsedContent parse(InputStream input, String contentType);

    /** Lower runs first; the general fallback uses a high value. */
    default int priority() {
        return 0;
    }
}
