package io.personalassistant.storage.mongo;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;

/**
 * Small, explicit helpers for mapping between the immutable domain records and BSON
 * {@link Document}s. Mapping is done by hand (rather than the POJO/record codec) so the shape
 * stored in Mongo is unambiguous and review-able, and so {@code Instant}/enum/free-form-map
 * conversions are handled consistently in one place.
 */
final class BsonSupport {

    private BsonSupport() {
    }

    static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date d) {
            return d.toInstant();
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Instant.parse(s);
        }
        return null;
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, Object value) {
        return value == null ? null : Enum.valueOf(type, value.toString());
    }

    static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * A rate-limit policy as {@code { rules: [ { permits, windowSeconds } ] }}, or null when unset.
     *
     * <p>Stored expanded rather than as the compact {@code "500/1m"} string the config properties use:
     * this one is written by the console, and a structured document is queryable and needs no parser on
     * the read path. An <em>empty</em> rules array is preserved and is not the same as absent — it is how
     * a user says "I removed the limit I had set".
     */
    static Document rateLimit(RateLimitPolicy policy) {
        if (policy == null) {
            return null;
        }
        List<Document> rules = new ArrayList<>(policy.rules().size());
        for (RateLimitRule rule : policy.rules()) {
            rules.add(new Document("permits", rule.permits())
                    .append("windowSeconds", rule.windowSeconds()));
        }
        return new Document("rules", rules);
    }

    static RateLimitPolicy rateLimitPolicy(Object value) {
        if (!(value instanceof Document doc)) {
            return null;
        }
        Object raw = doc.get("rules");
        if (!(raw instanceof List<?> list)) {
            return RateLimitPolicy.UNLIMITED;
        }
        List<RateLimitRule> rules = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Document rule) {
                Object permits = rule.get("permits");
                Object window = rule.get("windowSeconds");
                if (permits instanceof Number p && window instanceof Number w) {
                    rules.add(new RateLimitRule(p.intValue(), w.longValue()));
                }
            }
        }
        return new RateLimitPolicy(rules);
    }

    /** Read a nested sub-document, or null if absent. */
    static Document sub(Document parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        return v instanceof Document d ? d : null;
    }

    /**
     * Convert an arbitrary free-form value (used for {@code raw}/{@code inputs}/{@code metadata})
     * into a BSON-friendly form. Maps/Lists are passed through (the driver encodes them); domain
     * temporal types are normalized to {@link Date}.
     */
    static Object toBson(Object value) {
        if (value instanceof Instant i) {
            return Date.from(i);
        }
        if (value instanceof Map<?, ?> m) {
            Document doc = new Document();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                doc.put(String.valueOf(e.getKey()), toBson(e.getValue()));
            }
            return doc;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(toBson(o));
            }
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toBsonMap(Map<String, Object> map) {
        if (map == null) {
            return new Document();
        }
        return (Map<String, Object>) toBson(map);
    }

    /** Recursively convert a stored BSON map back into a plain {@link Map} of JSON-friendly values. */
    static Map<String, Object> toPlainMap(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), toPlainValue(e.getValue()));
        }
        return out;
    }

    private static Object toPlainValue(Object value) {
        if (value instanceof Map<?, ?>) {
            return toPlainMap(value);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(toPlainValue(o));
            }
            return out;
        }
        if (value instanceof Date d) {
            return d.toInstant();
        }
        return value;
    }
}
