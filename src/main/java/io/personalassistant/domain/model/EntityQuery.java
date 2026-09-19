package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the console's entity browser is asking for, as one value rather than a growing parameter list.
 *
 * <p>Every listing filter lives here: a new one is a component on this record plus a clause in each
 * {@link io.personalassistant.storage.repository.EntityRepository} adapter, instead of another
 * positional argument threaded through the resource, the service and both adapters. That matters
 * because the console renders its filter bar from a descriptor list, so filters are expected to be
 * added — {@code entityType} and an added-since window are the obvious next two.
 *
 * <p><b>{@code DELETED} is excluded unless asked for by name.</b> A tombstoned entity is on its way
 * out and its chunks are already gone or going, so it is not a row a user can act on; the listing
 * used to return them, which is why a removed item could sit in the browser looking merely
 * "processing". An explicit {@code statuses} containing {@code DELETED} still returns them, so
 * nothing is unreachable — the default just stops being wrong.
 *
 * @param statuses      statuses to include; empty means "every status except {@code DELETED}"
 * @param titleContains case-insensitive substring matched against the item's title and its
 *                      {@code externalId} (the console falls back to the id when an item has no
 *                      title, so searching only the title would miss exactly those rows); null when
 *                      not filtering
 * @param iterableIds   the groups (folder, label, company) to include, by iterable id; empty means
 *                      every group. Served by the {@code (knowledgeId, iterableId, updatedAt, _id)}
 *                      index, so narrowing a large source to one company does not scan all of it
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

    /** Everything the browser shows by default: any status but {@code DELETED}, no other filter. */
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

    /** True when the caller named statuses; false means the default "all but DELETED" rule applies. */
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
