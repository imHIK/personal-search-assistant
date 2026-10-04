package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import java.io.InputStream;

public interface ContentParser {

    boolean supports(String contentType);

    /** The caller owns the stream. */
    ParsedContent parse(InputStream input, String contentType);

    /** Lower runs first; the general fallback uses a high value. */
    default int priority() {
        return 0;
    }
}
