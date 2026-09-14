package io.personalassistant.domain.model;

import java.util.List;
import java.util.Map;

/**
 * What to say, independent of where it is said.
 *
 * <p>Carries no rendered markup. Every platform renders differently — an HTML email, a WhatsApp message
 * with a length cap, Slack blocks — so a pre-rendered body would suit exactly one of them and have to be
 * parsed back apart by the rest. Rendering is the publisher's job.
 *
 * <p>The shape is the one a digest run projects onto without loss: a heading, an optional line of
 * context, an optional written summary, and a list of results, each with whatever a task said about it
 * in {@link Item#fields}.
 *
 * @param title   the subject line / heading
 * @param intro   plain text shown above everything else, or null
 * @param items   the results; may be empty for a message that is all text
 * @param link    where to read more (the console page for the run, say), or null
 * @param summary prose written about the items, as <strong>Markdown</strong>, or null. The one field that
 *                is not plain text, because what fills it — a digest task's reply — is Markdown already,
 *                and Markdown is the closest thing to a format every platform can take: HTML for email,
 *                near-native for Slack and WhatsApp, readable as-is anywhere else
 */
public record PublishMessage(String title, String intro, List<Item> items, String link, String summary) {

    public PublishMessage {
        items = items == null ? List.of() : List.copyOf(items);
        intro = intro == null || intro.isBlank() ? null : intro;
        link = link == null || link.isBlank() ? null : link;
        summary = summary == null || summary.isBlank() ? null : summary;
    }

    /** A message with no summary. */
    public PublishMessage(String title, String intro, List<Item> items, String link) {
        this(title, intro, items, link, null);
    }

    /** True when there is nothing to send — no title, intro, summary or items. */
    public boolean isEmpty() {
        return (title == null || title.isBlank()) && intro == null && summary == null && items.isEmpty();
    }

    /**
     * One result.
     *
     * @param title  display title
     * @param uri    where to open it, or null
     * @param text   a short excerpt, or null
     * @param fields labelled values to show beside it — a digest's annotations. Open for the same reason
     *               {@code DigestRun.Item#annotations} is: the keys come from user-written tasks
     */
    public record Item(String title, String uri, String text, Map<String, Object> fields) {

        public Item {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
