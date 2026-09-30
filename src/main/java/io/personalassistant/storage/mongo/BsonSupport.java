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

/** Mapped by hand rather than by codec, so the stored shape is explicit. */
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
     * Stored expanded rather than as the compact string, since the console writes it. An empty rules array
     * stays distinct from absent: it is how a user removes a limit.
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

    static Document sub(Document parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        return v instanceof Document d ? d : null;
    }

    /** Maps and lists pass through; temporal values become Date. */
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
