package io.personalassistant.publishing.email;

import io.personalassistant.domain.model.PublishMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Email rendering: untrusted text stays text, only web links become links, the subject stays one line, and
 * raw extracted excerpts are tidied before anyone has to read them.
 */
class EmailRendererTest {

    private final EmailRenderer renderer = new EmailRenderer();

    /** A Google Doc excerpt as extraction really delivered it: a BOM, then paragraph spacing as bare CRLFs. */
    private static final String GOOGLE_DOC_EXCERPT = "﻿Thinking rationally: A Symptom of an Aware India\r\n\r\n\r\n"
            + "by Harshil Kapasi" + "\r\n".repeat(19) + "1. The strong foundation" + "\r\n".repeat(40) + "…";

    @Test
    void rendersTitleIntroItemsAndFieldsInBothBodies() {
        PublishMessage message = new PublishMessage("New roles", "Two new postings.", List.of(
                new PublishMessage.Item("Backend engineer", "https://www.jobs.example/1", "Remote, EU",
                        Map.of("fit", 8))), "https://console.example/digests/dig_1");

        RenderedEmail email = renderer.render(message, null);

        Assertions.assertEquals("New roles", email.subject());
        Assertions.assertTrue(email.html().contains("<a href=\"https://www.jobs.example/1\""), email.html());
        Assertions.assertTrue(email.html().contains(">jobs.example</div>"), "the source's host, without www.");
        Assertions.assertTrue(email.html().contains("Remote, EU"));
        Assertions.assertTrue(email.html().contains("fit</strong> 8"), email.html());
        Assertions.assertTrue(email.html().contains("Results (1)"));
        Assertions.assertTrue(email.html().contains("href=\"https://console.example/digests/dig_1\""));
        Assertions.assertTrue(email.text().contains("1. Backend engineer\n   https://www.jobs.example/1"), email.text());
        Assertions.assertTrue(email.text().contains("fit: 8"));
        Assertions.assertTrue(email.text().contains("https://console.example/digests/dig_1"));
    }

    @Test
    void theInboxPreviewLineNamesTheResults() {
        PublishMessage message = new PublishMessage("t", null, List.of(
                new PublishMessage.Item("Resume.pdf", null, null, null),
                new PublishMessage.Item("Rational Thinking", null, null, null),
                new PublishMessage.Item("Holidays", null, null, null),
                new PublishMessage.Item("Fourth", null, null, null)), null);

        String html = renderer.render(message, null).html();

        Assertions.assertTrue(html.contains("Resume.pdf · Rational Thinking · Holidays</div>"), html);
        Assertions.assertFalse(html.contains("Holidays · Fourth"), "a preview is a line, not the whole list");
    }

    @Test
    void untrustedTextIsEscaped() {
        PublishMessage message = new PublishMessage("<b>t</b>", "a & \"b\"", List.of(
                new PublishMessage.Item("<script>alert(1)</script>", null, "<img src=x>",
                        Map.of("<k>", "<v>"))), null);

        String html = renderer.render(message, null).html();

        Assertions.assertFalse(html.contains("<script>"));
        Assertions.assertFalse(html.contains("<img"));
        Assertions.assertFalse(html.contains("<b>t</b>"));
        Assertions.assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        Assertions.assertTrue(html.contains("a &amp; &quot;b&quot;"));
        Assertions.assertTrue(html.contains("&lt;k&gt;</strong> &lt;v&gt;"));
    }

    @Test
    void onlyWebLinksBecomeLinks() {
        PublishMessage message = new PublishMessage("t", null, List.of(
                new PublishMessage.Item("Local file", "file:///etc/passwd", null, null),
                new PublishMessage.Item("Script", "javascript:alert(1)", null, null)),
                "javascript:alert(2)");

        RenderedEmail email = renderer.render(message, null);

        Assertions.assertFalse(email.html().contains("href"), email.html());
        Assertions.assertFalse(email.text().contains("javascript:"));
        Assertions.assertFalse(email.text().contains("file:"));
    }

    @Test
    void theSubjectIsPrefixedAndKeptToOneLine() {
        RenderedEmail email = renderer.render(
                new PublishMessage("Line one\r\nBcc: someone@else", null, List.of(), null), "[digest]");

        Assertions.assertEquals("[digest] Line one Bcc: someone@else", email.subject());
    }

    @Test
    void anUntitledMessageGetsAFallbackSubject() {
        RenderedEmail email = renderer.render(new PublishMessage(null, "Body only", List.of(), null), null);

        Assertions.assertFalse(email.subject().isBlank());
    }

    @Test
    void theSummaryIsRenderedAsStyledMarkdownWithoutTrustingIt() {
        PublishMessage message = new PublishMessage("t", null, List.of(), null,
                "## Who\n\n**Two** strong fits [1].\n\n<script>alert(1)</script>\n\n[bad](javascript:alert(2))");

        RenderedEmail email = renderer.render(message, null);

        Assertions.assertTrue(email.html().contains("<strong>Two</strong>"), email.html());
        Assertions.assertTrue(email.html().contains("<h2 style=\"margin:10px 0 6px;font-size:15px;"),
                "a model's heading is sized for the panel, not the browser default");
        Assertions.assertFalse(email.html().contains("<script>"), email.html());
        Assertions.assertFalse(email.html().contains("javascript:"), email.html());
        Assertions.assertTrue(email.text().contains("**Two** strong fits [1]."),
                "the text part keeps the Markdown as written");
    }

    @Test
    void aGoogleDocExcerptLosesItsBlankLinesButKeepsItsParagraphs() {
        PublishMessage message = new PublishMessage("t", null, List.of(
                new PublishMessage.Item("Rational Thinking", "https://docs.google.com/document/d/1", GOOGLE_DOC_EXCERPT,
                        null)), null);

        RenderedEmail email = renderer.render(message, null);

        String expected = "Thinking rationally: A Symptom of an Aware India\n\nby Harshil Kapasi\n\n1. The strong foundation\n\n…";
        Assertions.assertTrue(email.html().contains(expected), email.html());
        Assertions.assertFalse(email.html().contains("\r"));
        Assertions.assertFalse(email.html().contains("﻿"));
        Assertions.assertTrue(email.text().contains("   Thinking rationally: A Symptom of an Aware India\n   \n   by Harshil Kapasi"),
                email.text());
    }

    @Test
    void cleaningKeepsLineBreaksAndTheJoinersScriptsNeed() {
        Assertions.assertNull(EmailRenderer.clean(null));
        Assertions.assertNull(EmailRenderer.clean(" \r\n​﻿\t\r\n "));
        Assertions.assertEquals("17 Dussehra 20/10/2026\n18 Diwali 08/11/2026",
                EmailRenderer.clean("17 Dussehra 20/10/2026   \n18 Diwali 08/11/2026"),
                "single line breaks survive; only trailing spaces go");
        Assertions.assertEquals("a b", EmailRenderer.clean("a\tb"));
        Assertions.assertEquals("क्‍ष 👩‍💻", EmailRenderer.clean("क्‍ष 👩‍💻"),
                "zero-width joiners are part of Indic conjuncts and emoji, not noise");
    }
}
