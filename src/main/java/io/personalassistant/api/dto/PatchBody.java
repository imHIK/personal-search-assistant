package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.service.Patched;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A PATCH request body, read as a tree so that <em>which keys were sent</em> survives.
 *
 * <p><b>Why not bind to a record of fields.</b> Jackson gives an absent key and an explicit
 * {@code null} the same null reference, so a bound patch cannot tell "leave this alone" from "clear
 * this". Every console control whose job is to turn something <em>off</em> sends the second and got the
 * first: a digest's look-back window or task could be set but never unset, and a source moved onto a
 * custom cron could never be moved back to a preset interval, because the {@code cron: null} that
 * should have released it was read as "unchanged". All of it answered 200. ({@code Optional} components
 * do not help — Jackson fills a missing key with {@link java.util.Optional#empty()}, collapsing the
 * same two states in the other direction, so patching one field would clear every other.)
 *
 * <p>Reading the tree costs the compile-time shape of a DTO. In exchange a wrong-typed value becomes a
 * 400 instead of an edit that silently does nothing — which is the failure this class exists to end.
 */
record PatchBody(JsonNode node) {

    /** True when the request carried a JSON object at all. */
    boolean isObject() {
        return node != null && node.isObject();
    }

    Patched<String> text(String key) {
        return read(key, value -> {
            if (!value.isTextual()) {
                throw wrongType(key, "a string");
            }
            return value.asText();
        });
    }

    /**
     * A string that has no meaningful empty state, so an explicit null is a mistake worth reporting
     * rather than a value worth writing. A knowledge with no name renders as a blank row forever.
     */
    Patched<String> requiredText(String key) {
        Patched<String> patched = text(key);
        if (patched.present() && patched.value() == null) {
            throw new IllegalArgumentException(key + " must not be null");
        }
        return patched;
    }

    Patched<Integer> integer(String key) {
        return read(key, value -> {
            if (!value.canConvertToInt()) {
                throw wrongType(key, "a whole number");
            }
            return value.asInt();
        });
    }

    Patched<Boolean> bool(String key) {
        return read(key, value -> {
            if (!value.isBoolean()) {
                throw wrongType(key, "true or false");
            }
            return value.asBoolean();
        });
    }

    Patched<List<String>> strings(String key) {
        return read(key, value -> {
            if (!value.isArray()) {
                throw wrongType(key, "an array of strings");
            }
            List<String> out = new ArrayList<>(value.size());
            for (JsonNode element : value) {
                if (!element.isTextual()) {
                    throw wrongType(key, "an array of strings");
                }
                out.add(element.asText());
            }
            return List.copyOf(out);
        });
    }

    Patched<Map<String, Object>> map(String key) {
        return read(key, value -> {
            if (!value.isObject()) {
                throw wrongType(key, "an object");
            }
            return object(value);
        });
    }

    /**
     * A map that has no meaningful null state — clearing one means sending {@code {}}, which is a
     * different and expressible thing.
     */
    Patched<Map<String, Object>> requiredMap(String key) {
        Patched<Map<String, Object>> patched = map(key);
        if (patched.present() && patched.value() == null) {
            throw new IllegalArgumentException(key + " must not be null");
        }
        return patched;
    }

    /**
     * The raw node, for a shape only the caller knows how to read — an array of objects, say. Comes
     * back with the same three states as everything else.
     */
    Patched<JsonNode> node(String key) {
        return read(key, value -> value);
    }

    /** The three states, in one place: the key is missing, the key is null, or it carries a value. */
    private <T> Patched<T> read(String key, Function<JsonNode, T> parse) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null) {
            return Patched.absent();
        }
        if (value.isNull()) {
            return Patched.of(null);
        }
        return Patched.of(parse.apply(value));
    }

    /**
     * Null entries are dropped rather than stored. {@code Map.copyOf} rejects a null value outright,
     * and a filter or input whose value is null says nothing a missing key does not already say.
     */
    private static Map<String, Object> object(JsonNode value) {
        Map<String, Object> out = new LinkedHashMap<>();
        value.fields().forEachRemaining(field -> {
            if (!field.getValue().isNull()) {
                out.put(field.getKey(), plain(field.getValue()));
            }
        });
        return Map.copyOf(out);
    }

    /** A free-form value as the storage layer and the search API can both carry it. */
    private static Object plain(JsonNode value) {
        if (value.isNumber()) {
            return value.isIntegralNumber() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isObject()) {
            return object(value);
        }
        if (value.isArray()) {
            List<Object> nested = new ArrayList<>(value.size());
            for (JsonNode element : value) {
                if (!element.isNull()) {
                    nested.add(plain(element));
                }
            }
            return List.copyOf(nested);
        }
        return value.asText();
    }

    private static IllegalArgumentException wrongType(String key, String expected) {
        return new IllegalArgumentException(key + " must be " + expected);
    }
}
