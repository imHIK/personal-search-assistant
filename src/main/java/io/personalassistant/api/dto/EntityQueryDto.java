package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @param filters keyed by path. A scalar is equality, an array is any-of, and an object holds operators:
 *                {@code eq ne in nin gte gt lte lt contains exists}. An ISO date or instant in a range is
 *                compared as a date
 * @param sort {@code {"path": "createdAt", "descending": true}} when absent
 */
public record EntityQueryDto(
        List<String> entityTypes,
        List<String> knowledgeIds,
        String q,
        Map<String, JsonNode> filters,
        SortDto sort,
        Integer limit,
        Integer offset) {

    public record SortDto(String path, Boolean descending) {}

    public static final int DEFAULT_LIMIT = 50;

    /** @throws IllegalArgumentException on an unknown type, path, operator or value shape */
    public EntityFilter toFilter() {
        Set<EntityType> types = new LinkedHashSet<>();
        if (entityTypes != null) {
            for (String type : entityTypes) {
                types.add(EntityType.valueOf(type.trim().toUpperCase(Locale.ROOT)));
            }
        }
        List<EntityFilter.Condition> conditions = new ArrayList<>();
        if (filters != null) {
            filters.forEach((path, node) -> conditions.addAll(conditionsFor(path, node)));
        }
        EntityFilter.Sort order = sort == null || sort.path() == null || sort.path().isBlank()
                ? null
                : new EntityFilter.Sort(sort.path(), !Boolean.FALSE.equals(sort.descending()));
        return new EntityFilter(types, knowledgeIds == null ? null : Set.copyOf(knowledgeIds), q, conditions,
                order);
    }

    public int limitOrDefault() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    public int offsetOrDefault() {
        return offset == null ? 0 : offset;
    }

    private static List<EntityFilter.Condition> conditionsFor(String path, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of(new EntityFilter.Condition(path, EntityFilter.Op.EXISTS, false));
        }
        if (node.isArray()) {
            return List.of(new EntityFilter.Condition(path, EntityFilter.Op.IN, list(node)));
        }
        if (!node.isObject()) {
            return List.of(new EntityFilter.Condition(path, EntityFilter.Op.EQ, scalar(node)));
        }
        List<EntityFilter.Condition> out = new ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            EntityFilter.Op op = op(entry.getKey());
            JsonNode value = entry.getValue();
            Object parsed = switch (op) {
                case IN, NIN -> list(value);
                case EXISTS -> value.asBoolean();
                case CONTAINS -> value.asText();
                default -> scalar(value);
            };
            out.add(new EntityFilter.Condition(path, op, parsed));
        });
        return out;
    }

    private static EntityFilter.Op op(String name) {
        try {
            return EntityFilter.Op.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown filter operator \"" + name
                    + "\"; use eq, ne, in, nin, gte, gt, lte, lt, contains or exists");
        }
    }

    private static List<Object> list(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("in / nin take an array");
        }
        List<Object> out = new ArrayList<>();
        node.forEach(element -> out.add(scalar(element)));
        return out;
    }

    private static Object scalar(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return dateOrText(node.asText());
        }
        throw new IllegalArgumentException("A filter value must be text, a number or true/false");
    }

    /** Dates are stored as BSON dates, so an ISO string must become an Instant to compare against them. */
    private static Object dateOrText(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notInstant) {
            try {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notDate) {
                return text;
            }
        }
    }
}
