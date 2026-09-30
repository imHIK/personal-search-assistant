package io.personalassistant.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pagination state defined entirely by the connector; the core only persists and replays it. */
public record CursorPosition(Map<String, Object> values) {

    public CursorPosition {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static CursorPosition start() {
        return new CursorPosition(Map.of());
    }

    public static CursorPosition of(Map<String, Object> values) {
        return new CursorPosition(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isStart() {
        return values.isEmpty();
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v == null ? null : v.toString();
    }

    public Long getLong(String key) {
        Object v = values.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }

    public long getLong(String key, long defaultValue) {
        Long v = getLong(key);
        return v == null ? defaultValue : v;
    }

    public Integer getInt(String key) {
        Long v = getLong(key);
        return v == null ? null : v.intValue();
    }

    public Builder toBuilder() {
        return new Builder().putAll(values);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        public Builder putAll(Map<String, Object> other) {
            if (other != null) {
                other.forEach(this::put);
            }
            return this;
        }

        public CursorPosition build() {
            return new CursorPosition(values);
        }
    }
}
