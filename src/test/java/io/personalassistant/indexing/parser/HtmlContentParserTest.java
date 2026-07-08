package io.personalassistant.indexing.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.ParsedContent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HtmlContentParserTest {

    @Test
    void extractsVisibleTextAndStripsMarkupAndScripts() {
        String html = "<html><head><title>Doc</title></head>"
                + "<body><h1>Heading</h1><p>Hello <a href=\"http://example.com\">world</a>.</p>"
                + "<script>var secret = 42;</script></body></html>";

        ParsedContent parsed = new HtmlContentParser().parse(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), "text/html");
        String text = parsed.text();

        assertTrue(text.contains("Heading"), "headings kept");
        assertTrue(text.contains("Hello"), "body text kept");
        assertTrue(text.contains("world"), "link text kept");
        assertFalse(text.contains("<h1>"), "tags stripped");
        assertFalse(text.contains("var secret"), "script contents stripped");
        assertEquals("html", parsed.metadata().get("parser"));
    }
}
