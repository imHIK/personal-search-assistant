package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityType;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A cross-knowledge entity query on stored fields. DELETED is always excluded. Paths are restricted to the
 * fields a caller may legitimately filter on, so a query cannot reach credentials, leases or raw payloads.
 *
 * @param text case-insensitive substring of the title or external id; blank means none
 */
public record EntityFilter(Set<EntityType> entityTypes, Set<String> knowledgeIds, String text,
                           List<Condition> conditions, Sort sort) {

    private static final Pattern FILTERABLE =
            Pattern.compile("(metadata|enriched|custom)\\.[A-Za-z][A-Za-z0-9_]*|createdAt|updatedAt");

    public enum Op { EQ, NE, IN, NIN, GTE, GT, LTE, LT, CONTAINS, EXISTS }

    /**
     * @param value a scalar for EQ/NE/ranges (an Instant for a date), a list for IN/NIN, text for CONTAINS,
     *              a Boolean for EXISTS. On a list-valued field EQ and IN match any element
     */
    public record Condition(String path, Op op, Object value) {

        public Condition {
            requireFilterable(path);
        }
    }

    public record Sort(String path, boolean descending) {

        public static final Sort NEWEST_FIRST = new Sort("createdAt", true);

        public Sort {
            requireFilterable(path);
        }
    }

    public EntityFilter {
        entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
        knowledgeIds = knowledgeIds == null ? Set.of() : Set.copyOf(knowledgeIds);
        text = text == null || text.isBlank() ? null : text.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        sort = sort == null ? Sort.NEWEST_FIRST : sort;
    }

    /** The same scope without conditions, text or sort: what facet values are counted over. */
    public EntityFilter scopeOnly() {
        return new EntityFilter(entityTypes, knowledgeIds, null, List.of(), null);
    }

    /** @throws IllegalArgumentException naming the path */
    public static void requireFilterable(String path) {
        if (path == null || !FILTERABLE.matcher(path).matches()) {
            throw new IllegalArgumentException("\"" + path + "\" cannot be filtered or sorted on; use "
                    + "metadata.<field>, enriched.<field>, custom.<field>, createdAt or updatedAt");
        }
    }
}
