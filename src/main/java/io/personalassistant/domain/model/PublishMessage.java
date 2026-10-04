package io.personalassistant.domain.model;

import java.util.List;
import java.util.Map;

/**
 * What to say, independent of where: no rendered markup, since each platform renders differently.
 *
 * @param summary Markdown; the one field that is not plain text
 */
public record PublishMessage(String title, String intro, List<Item> items, String link, String summary) {

    public PublishMessage {
        items = items == null ? List.of() : List.copyOf(items);
        intro = intro == null || intro.isBlank() ? null : intro;
        link = link == null || link.isBlank() ? null : link;
        summary = summary == null || summary.isBlank() ? null : summary;
    }

    public PublishMessage(String title, String intro, List<Item> items, String link) {
        this(title, intro, items, link, null);
    }

    public boolean isEmpty() {
        return (title == null || title.isBlank()) && intro == null && summary == null && items.isEmpty();
    }

    /**
     * @param fields labelled values shown beside it (a digest's annotations); open because the keys come from
     *               user-written tasks
     */
    public record Item(String title, String uri, String text, Map<String, Object> fields) {

        public Item {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
