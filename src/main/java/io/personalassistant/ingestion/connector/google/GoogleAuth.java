package io.personalassistant.ingestion.connector.google;

import io.personalassistant.common.ratelimit.RateLimit;

/**
 * A bearer token together with the quota the calls made with it are charged against.
 *
 * <p>The two travel as one value because they come from the same place and are needed in the same place:
 * both are properties of the {@code Connection}, and the transport needs both. Passing only the token —
 * as this used to — left the rate limiter unable to tell two accounts apart, since the URL and host are
 * identical for every Google user. Bundling them keeps {@code GmailApi} / {@code DriveApi} at their
 * existing arity rather than growing a parameter on every method.
 *
 * @param bearer the OAuth 2.0 access token
 * @param limit  the account's quota; {@link RateLimit#NONE} when unthrottled
 */
public record GoogleAuth(String bearer, RateLimit limit) {

    public GoogleAuth {
        limit = limit == null ? RateLimit.NONE : limit;
    }

    public static GoogleAuth unlimited(String bearer) {
        return new GoogleAuth(bearer, RateLimit.NONE);
    }
}
