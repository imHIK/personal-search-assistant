package io.personalassistant.api.dto;

import io.personalassistant.domain.model.PublishMessage;
import java.util.List;
import java.util.Map;

/** Plain text except {@code summary}, which is Markdown. */
public record PublishMessageDto(String title, String intro, List<Item> items, String link, String summary) {

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
