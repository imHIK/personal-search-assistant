package io.personalassistant.ingestion.connector.google;

import io.personalassistant.common.ratelimit.RateLimit;

/** @param limit the account's quota; {@link RateLimit#NONE} when unthrottled */
public record GoogleAuth(String bearer, RateLimit limit) {

    public GoogleAuth {
        limit = limit == null ? RateLimit.NONE : limit;
    }

    public static GoogleAuth unlimited(String bearer) {
        return new GoogleAuth(bearer, RateLimit.NONE);
    }
}
