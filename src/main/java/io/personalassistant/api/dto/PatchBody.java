package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.service.Patched;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A PATCH body read as a tree, so an absent key and an explicit null stay distinct. Bound to a record,
 * Jackson gives both the same null, and Optional components collapse them the other way.
 */
record PatchBody(JsonNode node) {

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

    /** A string with no empty state: an explicit null is a 400, not a value. */
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

    /** A map with no null state; clearing one means sending {@code {}}. */
    Patched<Map<String, Object>> requiredMap(String key) {
        Patched<Map<String, Object>> patched = map(key);
        if (patched.present() && patched.value() == null) {
            throw new IllegalArgumentException(key + " must not be null");
        }
        return patched;
    }

    Patched<JsonNode> node(String key) {
        return read(key, value -> value);
    }

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
     * Null entries are dropped: {@code Map.copyOf} rejects them, and they say nothing a missing key doesn't.
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
