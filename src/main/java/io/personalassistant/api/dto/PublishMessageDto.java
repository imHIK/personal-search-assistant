package io.personalassistant.api.dto;

import io.personalassistant.domain.model.PublishMessage;
import java.util.List;
import java.util.Map;

/**
 * Wire shape for a message to publish. Plain text except {@code summary} — each channel renders it.
 *
 * @param title   subject line / heading
 * @param intro   text above the items, or null
 * @param items   results to list, or null
 * @param link    where to read more, or null
 * @param summary prose about the items, as Markdown, or null
 */
public record PublishMessageDto(String title, String intro, List<Item> items, String link, String summary) {

    /**
     * @param fields labelled values shown beside the item
     */
    public record Item(String title, String uri, String text, Map<String, Object> fields) {
    }

    public static PublishMessageDto from(PublishMessage m) {
        return m == null ? null : new PublishMessageDto(m.title(), m.intro(),
                m.items().stream().map(i -> new Item(i.title(), i.uri(), i.text(), i.fields())).toList(),
                m.link(), m.summary());
    }

    public PublishMessage toDomain() {
        return new PublishMessage(title, intro, items == null ? List.of() : items.stream()
                .map(i -> new PublishMessage.Item(i.title(), i.uri(), i.text(), i.fields())).toList(), link, summary);
    }
}
