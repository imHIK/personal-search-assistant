package io.personalassistant.indexing.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies MIME routing across the dedicated parsers: each claims its own family, the plain-text
 * parser deliberately yields HTML to the HTML parser, and dedicated parsers outrank the generic
 * Tika fallback (lower {@link ContentParser#priority()} wins).
 */
class ContentParserRoutingTest {

    @Test
    void dedicatedParsersClaimTheirOwnTypes() {
        assertTrue(new PdfContentParser().supports("application/pdf"));
        assertTrue(new WordDocumentParser().supports(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertTrue(new PresentationParser().supports(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        assertTrue(new SpreadsheetContentParser().supports(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(new HtmlContentParser().supports("text/html"));
    }

    @Test
    void contentTypeParametersAreIgnored() {
        assertTrue(new HtmlContentParser().supports("text/html; charset=utf-8"));
        assertTrue(new PdfContentParser().supports("APPLICATION/PDF"));
    }

    @Test
    void plainTextYieldsHtmlToTheHtmlParser() {
        assertFalse(new PlainTextParser().supports("text/html"),
                "plain text must not claim HTML — the HTML parser strips markup");
        assertTrue(new PlainTextParser().supports("text/markdown"));
        assertTrue(new PlainTextParser().supports("text/plain"));
        assertTrue(new PlainTextParser().supports("application/json"));
    }

    @Test
    void dedicatedParsersOutrankTheGenericFallback() {
        int fallback = new TikaContentParser().priority();
        assertTrue(new PdfContentParser().priority() < fallback);
        assertTrue(new HtmlContentParser().priority() < fallback);
        assertEquals(0, new PlainTextParser().priority(), "plain text is cheapest / most specific");
        assertTrue(new TikaContentParser().supports("application/x-anything"),
                "the fallback still claims everything so extraction never hard-fails");
    }
}
