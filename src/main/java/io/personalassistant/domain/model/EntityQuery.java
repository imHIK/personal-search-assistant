package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @param statuses empty means every status except DELETED
 * @param titleContains case-insensitive, matched against the title and externalId (the console shows the id
 *                      when there is no title)
 * @param iterableIds empty means every group
 */
public record EntityQuery(Set<EntityStatus> statuses, String titleContains, Set<String> iterableIds) {

    public EntityQuery {
        statuses = statuses == null || statuses.isEmpty()
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(statuses));
        titleContains = titleContains == null || titleContains.isBlank() ? null : titleContains.trim();
        iterableIds = iterableIds == null || iterableIds.isEmpty()
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(iterableIds));
    }

    public static EntityQuery all() {
        return new EntityQuery(Set.of(), null, Set.of());
    }

    public static EntityQuery ofStatuses(EntityStatus... statuses) {
        return new EntityQuery(new LinkedHashSet<>(Arrays.asList(statuses)), null, Set.of());
    }

    public EntityQuery withTitleContains(String text) {
        return new EntityQuery(statuses, text, iterableIds);
    }

    public EntityQuery withIterableIds(String... ids) {
        return new EntityQuery(statuses, titleContains, new LinkedHashSet<>(Arrays.asList(ids)));
    }

    public boolean hasStatusFilter() {
        return !statuses.isEmpty();
    }

    public boolean hasTextFilter() {
        return titleContains != null;
    }

    public boolean hasIterableFilter() {
        return !iterableIds.isEmpty();
    }
}
