package io.personalassistant.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pagination state of a {@link Cursor}, <strong>defined entirely by the source connector</strong>.
 * It is an opaque, free-form bag of fields so each integration can paginate its own way — a page
 * token, a numeric offset, a {@code (timestamp, lastId)} pair, a change-id, an mbox byte offset,
 * etc. The core never interprets it; it only persists and replays it.
 *
 * <p>Stored in Mongo as a sub-document and handed back to the connector on the next page, so a
 * connector can keep as many pagination fields as it needs without encoding them into a string.
 *
 * <p>Typical connector usage:
 * <pre>{@code
 *   // write
 *   CursorPosition next = CursorPosition.builder()
 *           .put("pageToken", token)
 *           .put("fetchedAt", Instant.now().toEpochMilli())
 *           .build();
 *   // read
 *   String token = position.getString("pageToken");
 *   long since   = position.getLong("fetchedAt", 0L);
 * }</pre>
 *
 * @param values the connector-defined fields; never null, treated as immutable
 */
public record CursorPosition(Map<String, Object> values) {

    public CursorPosition {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** The "beginning" position handed to a connector on the very first page. */
    public static CursorPosition start() {
        return new CursorPosition(Map.of());
    }

    public static CursorPosition of(Map<String, Object> values) {
        return new CursorPosition(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when no pagination has happened yet (the connector should start from the top). */
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

    /** Start a builder seeded with this position's fields (for incremental updates). */
    public Builder toBuilder() {
        return new Builder().putAll(values);
    }

    /** Fluent builder so connectors can assemble multi-field positions readably. */
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
