package io.personalassistant.publishing.email;

import io.personalassistant.domain.model.PublishMessage;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders a {@link PublishMessage} as an email: an inline-styled HTML body and a plain-text twin.
 *
 * <p>Hand-built rather than templated. The layout is one fixed shape — header, optional summary, a list
 * of results, a button back to the console — and a template engine would be a dependency for it. The
 * markup is deliberately old-fashioned: nested presentation tables and inline styles, because Gmail strips
 * {@code <style>} blocks and Outlook ignores most of modern CSS layout. Anything cleverer renders
 * differently in every inbox.
 *
 * <p>Everything in a message ultimately comes from indexed content or a model's reply, so every string is
 * escaped and only {@code http(s)} links are emitted. A {@code javascript:} or {@code file:} URI from a
 * crawled page is dropped rather than made clickable.
 */
@ApplicationScoped
public class EmailRenderer {

    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "SFMono-Regular,Menlo,Consolas,monospace";
    private static final String BRAND = "Personal Search Assistant";
    private static final String FALLBACK_SUBJECT = "Update from " + BRAND;

    /** Result titles shown in the inbox preview line, before it becomes a list nobody reads. */
    private static final int PREHEADER_TITLES = 3;

    // Palette, in one place so the whole message can be re-toned together.
    private static final String PAGE = "#f3f4f6";
    private static final String CARD = "#ffffff";
    private static final String BORDER = "#e5e7eb";
    private static final String RULE = "#eef0f3";
    private static final String HEADING = "#111827";
    private static final String BODY = "#374151";
    private static final String MUTED = "#6b7280";
    private static final String FAINT = "#9ca3af";
    private static final String ACCENT = "#1d4ed8";
    private static final String ACCENT_SOFT = "#eef2ff";
    private static final String ACCENT_TEXT = "#3b5bdb";
    private static final String SUMMARY_BG = "#f5f8ff";
    private static final String SUMMARY_BORDER = "#dbe4ff";

    private static final Parser MARKDOWN = Parser.builder().build();

    /**
     * {@code escapeHtml} keeps raw HTML in a model's reply as visible text rather than markup, and
     * {@code sanitizeUrls} drops {@code javascript:} and other non-web link targets — the summary is model
     * output over indexed content, and gets no more trust than the rest of the message. The attribute
     * provider inlines a style on each element, since a mail client will not apply a stylesheet: a model
     * that answers with {@code ## Heading} would otherwise get a browser-default 24px heading inside a
     * 14px panel.
     */
    private static final HtmlRenderer MARKDOWN_HTML = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                String style = markdownStyle(tagName);
                if (style != null) {
                    attributes.put("style", style);
                }
            })
            .build();

    /**
     * @param subjectPrefix prepended to the subject, e.g. {@code "[digest]"}; null for none
     */
    public RenderedEmail render(PublishMessage message, String subjectPrefix) {
        return new RenderedEmail(subject(message, subjectPrefix), html(message), text(message));
    }

    private static String subject(PublishMessage message, String prefix) {
        String title = blank(message.title()) ? FALLBACK_SUBJECT : message.title();
        String subject = blank(prefix) ? title : prefix.trim() + " " + title;
        // A newline in a header value is header injection; a subject is one line by definition.
        return subject.replaceAll("[\\r\\n]+", " ").trim();
    }

    // ---- HTML ------------------------------------------------------------------------------------------

    private static String html(PublishMessage message) {
        StringBuilder out = new StringBuilder();
        String preheader = preheader(message);
        if (preheader != null) {
            // The inbox list shows the first text in the body. Without this that is the brand line.
            out.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;"
                    + "mso-hide:all\">").append(escape(preheader)).append("</div>");
        }
        out.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                        + "style=\"background:").append(PAGE).append(";\"><tr><td align=\"center\" "
                        + "style=\"padding:24px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "border=\"0\" style=\"max-width:640px;background:").append(CARD)
                .append(";border:1px solid ").append(BORDER).append(";border-radius:12px;\">");

        header(out, message);
        if (message.summary() != null) {
            summary(out, message.summary());
        }
        if (!message.items().isEmpty()) {
            out.append(row("padding:24px 28px 4px;font-size:12px;font-weight:600;letter-spacing:0.06em;"
                    + "text-transform:uppercase;color:" + MUTED + ";"))
                    .append("Results (").append(message.items().size()).append(")").append(END_ROW);
            int rank = 1;
            for (PublishMessage.Item item : message.items()) {
                item(out, item, rank++);
            }
        }
        footer(out, safeUri(message.link()));

        out.append("</table>")
                .append("<div style=\"font-family:").append(FONT).append(";font-size:12px;color:").append(FAINT)
                .append(";padding:14px 12px 0;\">Sent by ").append(BRAND).append("</div>")
                .append("</td></tr></table>");
        return out.toString();
    }

    private static void header(StringBuilder out, PublishMessage message) {
        out.append(row("padding:28px 28px 4px;"))
                .append("<div style=\"font-size:12px;font-weight:600;letter-spacing:0.06em;text-transform:uppercase;"
                        + "color:").append(MUTED).append(";\">").append(BRAND).append("</div>");
        if (!blank(message.title())) {
            out.append("<h1 style=\"margin:6px 0 0;font-size:22px;line-height:1.3;font-weight:700;color:")
                    .append(HEADING).append(";\">").append(escape(oneLine(message.title()))).append("</h1>");
        }
        String intro = clean(message.intro());
        if (intro != null) {
            out.append("<p style=\"margin:12px 0 0;font-size:15px;line-height:1.6;color:").append(BODY)
                    .append(";white-space:pre-line;\">").append(escape(intro)).append("</p>");
        }
        out.append(END_ROW);
    }

    private static void summary(StringBuilder out, String markdown) {
        out.append(row("padding:18px 28px 0;"))
                .append("<div style=\"background:").append(SUMMARY_BG).append(";border:1px solid ")
                .append(SUMMARY_BORDER).append(";border-radius:10px;padding:14px 18px;\">")
                .append("<div style=\"font-size:11px;font-weight:600;letter-spacing:0.06em;text-transform:uppercase;"
                        + "color:").append(ACCENT_TEXT).append(";margin-bottom:6px;\">Summary</div>")
                .append("<div style=\"font-size:14px;line-height:1.6;color:").append(BODY).append(";\">")
                .append(MARKDOWN_HTML.render(MARKDOWN.parse(markdown)))
                .append("</div></div>").append(END_ROW);
    }

    private static void item(StringBuilder out, PublishMessage.Item item, int rank) {
        String title = blank(item.title()) ? "(untitled)" : oneLine(clean(item.title()));
        String uri = safeUri(item.uri());
        String host = host(uri);
        String excerpt = clean(item.text());

        out.append(row("padding:0 28px;"))
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                        + "style=\"border-top:1px solid ").append(RULE).append(";\"><tr>")
                .append("<td width=\"34\" valign=\"top\" style=\"padding:16px 0;\">")
                .append("<div style=\"width:24px;height:24px;line-height:24px;border-radius:12px;background:")
                .append(ACCENT_SOFT).append(";color:").append(ACCENT_TEXT)
                .append(";font-size:12px;font-weight:600;text-align:center;\">").append(rank).append("</div></td>")
                .append("<td valign=\"top\" style=\"padding:16px 0 16px 6px;font-family:").append(FONT).append(";\">");

        if (uri == null) {
            out.append("<div style=\"font-size:15px;font-weight:600;line-height:1.4;color:").append(HEADING)
                    .append(";\">").append(escape(title)).append("</div>");
        } else {
            out.append("<a href=\"").append(escape(uri)).append("\" style=\"font-size:15px;font-weight:600;"
                    + "line-height:1.4;color:").append(ACCENT).append(";text-decoration:none;\">")
                    .append(escape(title)).append("</a>");
        }
        if (host != null) {
            out.append("<div style=\"font-size:12px;color:").append(FAINT).append(";margin-top:2px;\">")
                    .append(escape(host)).append("</div>");
        }
        if (!item.fields().isEmpty()) {
            // Before the excerpt, as the console orders them: what the task concluded leads the row.
            out.append("<div style=\"margin-top:8px;\">");
            for (Map.Entry<String, Object> field : item.fields().entrySet()) {
                out.append("<span style=\"display:inline-block;margin:0 6px 6px 0;padding:2px 9px;border-radius:999px;"
                                + "background:").append(PAGE).append(";color:").append(BODY)
                        .append(";font-size:12px;line-height:1.6;\"><strong style=\"font-weight:600;color:")
                        .append(HEADING).append(";\">").append(escape(field.getKey())).append("</strong> ")
                        .append(escape(String.valueOf(field.getValue()))).append("</span>");
            }
            out.append("</div>");
        }
        if (excerpt != null) {
            out.append("<div style=\"margin-top:6px;font-size:13px;line-height:1.55;color:").append(MUTED)
                    .append(";white-space:pre-line;\">").append(escape(excerpt)).append("</div>");
        }
        out.append("</td></tr></table>").append(END_ROW);
    }

    private static void footer(StringBuilder out, String link) {
        out.append(row("padding:20px 28px 28px;"));
        if (link != null) {
            out.append("<a href=\"").append(escape(link)).append("\" style=\"display:inline-block;padding:10px 18px;"
                            + "border-radius:8px;background:").append(ACCENT).append(";color:#ffffff;font-size:14px;"
                            + "font-weight:600;text-decoration:none;\">Open in ").append(BRAND).append("</a>");
        }
        out.append(END_ROW);
    }

    private static final String END_ROW = "</td></tr>";

    private static String row(String style) {
        return "<tr><td style=\"font-family:" + FONT + ";" + style + "\">";
    }

    /** Inline styles for the elements a Markdown summary produces; null leaves an element as rendered. */
    private static String markdownStyle(String tagName) {
        return switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                    "margin:10px 0 6px;font-size:15px;line-height:1.4;font-weight:700;color:" + HEADING + ";";
            case "p" -> "margin:0 0 8px;";
            case "ul", "ol" -> "margin:0 0 8px;padding-left:20px;";
            case "li" -> "margin:2px 0;";
            case "a" -> "color:" + ACCENT + ";";
            case "code" -> "font-family:" + MONO + ";font-size:12px;background:#eef1f5;padding:1px 4px;border-radius:4px;";
            case "pre" -> "margin:0 0 8px;padding:10px;background:#eef1f5;border-radius:6px;overflow:auto;";
            case "blockquote" -> "margin:0 0 8px;padding-left:10px;border-left:3px solid " + BORDER + ";color:" + MUTED + ";";
            default -> null;
        };
    }

    // ---- plain text ------------------------------------------------------------------------------------

    private static String text(PublishMessage message) {
        StringBuilder out = new StringBuilder();
        if (!blank(message.title())) {
            String title = oneLine(message.title());
            out.append(title).append('\n').append("=".repeat(Math.min(title.length(), 60))).append("\n\n");
        }
        String intro = clean(message.intro());
        if (intro != null) {
            out.append(intro).append("\n\n");
        }
        if (message.summary() != null) {
            // Markdown is written to be read as it stands, which is what a plain-text part is for.
            out.append("SUMMARY\n").append(message.summary().strip()).append("\n\n");
        }
        if (!message.items().isEmpty()) {
            out.append("RESULTS (").append(message.items().size()).append(")\n\n");
        }
        int rank = 1;
        for (PublishMessage.Item item : message.items()) {
            out.append(rank++).append(". ")
                    .append(blank(item.title()) ? "(untitled)" : oneLine(clean(item.title()))).append('\n');
            String uri = safeUri(item.uri());
            if (uri != null) {
                out.append("   ").append(uri).append('\n');
            }
            if (!item.fields().isEmpty()) {
                List<String> fields = new ArrayList<>();
                item.fields().forEach((key, value) -> fields.add(key + ": " + value));
                out.append("   ").append(String.join(" · ", fields)).append('\n');
            }
            String excerpt = clean(item.text());
            if (excerpt != null) {
                out.append("   ").append(excerpt.replace("\n", "\n   ")).append('\n');
            }
            out.append('\n');
        }
        String link = safeUri(message.link());
        if (link != null) {
            out.append("Open in ").append(BRAND).append(": ").append(link).append('\n');
        }
        return out.toString().stripTrailing() + "\n";
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /**
     * An excerpt tidied for reading, or null when nothing readable is left.
     *
     * <p>Excerpts are raw extracted text, and extraction keeps whatever the source had: a Google Doc exports
     * every empty paragraph as a {@code \r\n}, so a title followed by spacing paragraphs arrives as dozens
     * of blank lines — rendered faithfully, that pushed the rest of a digest a screen further down and looked
     * like the email had ended. So: line endings are normalised; control and invisible format characters
     * (a byte-order mark, zero-width spaces) are dropped, except the zero-width joiners that emoji sequences
     * and Indic scripts need; trailing spaces go; and any run of blank lines becomes one. Single line breaks
     * are kept — a list or a table of dates still reads line by line.
     */
    // Package-private for tests.
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder kept = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\t') {
                kept.append(' ');
            } else if (c == '\n') {
                kept.append(c);
            } else if (c == '\u200C' || c == '\u200D') {
                kept.append(c);
            } else if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) {
                kept.append(c);
            }
        }
        String tidy = kept.toString()
                .replaceAll("[ \\u00A0]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        return tidy.isEmpty() ? null : tidy;
    }

    /** The inbox preview line: the intro if there is one, else the first few result titles. */
    private static String preheader(PublishMessage message) {
        String intro = clean(message.intro());
        if (intro != null) {
            return oneLine(intro);
        }
        List<String> titles = new ArrayList<>();
        for (PublishMessage.Item item : message.items()) {
            String title = clean(item.title());
            if (title != null) {
                titles.add(oneLine(title));
            }
            if (titles.size() == PREHEADER_TITLES) {
                break;
            }
        }
        return titles.isEmpty() ? null : String.join(" · ", titles);
    }

    /** A link's host without {@code www.}, so a reader can see where a result lives before opening it. */
    private static String host(String safeUri) {
        if (safeUri == null) {
            return null;
        }
        try {
            String host = URI.create(safeUri).getHost();
            return host == null ? null : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String oneLine(String value) {
        return value == null ? null : value.replaceAll("\\s*\\n\\s*", " ").strip();
    }

    /** The URI if it is an absolute http(s) link, else null. */
    // Package-private for tests.
    static String safeUri(String uri) {
        if (blank(uri)) {
            return null;
        }
        String trimmed = uri.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? trimmed : null;
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
