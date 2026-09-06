package io.personalassistant.common.http;

import io.personalassistant.common.ratelimit.RateLimit;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One outbound request, described as data so it can carry the thing a request alone cannot: which
 * quota it is charged against.
 *
 * <p>That is the whole reason this record exists rather than a pile of overloads. The rate-limit bucket
 * is never derivable from the URL — two Gmail accounts hit the same host under different quotas, and one
 * SmartRecruiters grab is many requests under one — so it has to be stated by the caller who knows it,
 * and travel with the request to the single place limits are enforced.
 *
 * @param method  HTTP method
 * @param url     absolute request URL
 * @param headers request headers; never null
 * @param body    request body, or null for methods that carry none
 * @param timeout per-request timeout
 * @param limit   the quota this request is charged against; {@link RateLimit#NONE} to skip
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

    /** @return a copy with one more header; blank values are dropped so callers need no null checks */
    public HttpCall header(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new HttpCall(method, url, merged, body, timeout, limit);
    }

    /** @return a copy accepting JSON, the shape almost every caller here wants */
    public HttpCall acceptJson() {
        return header("Accept", "application/json");
    }
}
