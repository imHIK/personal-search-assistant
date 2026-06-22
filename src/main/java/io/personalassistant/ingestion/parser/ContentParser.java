package io.personalassistant.ingestion.parser;

import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.RawItem;

/**
 * Extracts plain text (and incidental metadata) from a raw item. One implementation per
 * family of content types (PDF, DOCX, HTML, plain text, images via OCR…). Selected by
 * MIME type through the {@link ParserRegistry}.
 */
public interface ContentParser {

    /** @return true if this parser can handle the given MIME type */
    boolean supports(String contentType);

    /** Parse the item's bytes into normalized text + metadata. */
    ParsedContent parse(RawItem item);
}
