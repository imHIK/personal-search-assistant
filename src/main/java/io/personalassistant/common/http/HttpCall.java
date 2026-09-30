package io.personalassistant.common.http;

import io.personalassistant.common.ratelimit.RateLimit;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An outbound request plus the quota it is charged to, which the URL cannot tell: two accounts share a host
 * under different quotas.
 *
 * @param limit {@link RateLimit#NONE} to skip
 */
public record HttpCall(String method, String url, Map<String, String> headers, String body,
                       Duration timeout, RateLimit limit) {

    public HttpCall {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        limit = limit == null ? RateLimit.NONE : limit;
    }

    public static HttpCall get(String url, Duration timeout, RateLimit limit) {
        return new HttpCall("GET", url, Map.of(), null, timeout, limit);
    }

    public static HttpCall post(String url, String body, Duration timeout, RateLimit limit) {
        return new HttpCall("POST", url, Map.of(), body, timeout, limit);
    }

    /** Blank values are dropped. */
    public HttpCall header(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new HttpCall(method, url, merged, body, timeout, limit);
    }

    public HttpCall acceptJson() {
        return header("Accept", "application/json");
    }
}
