package io.personalassistant.common.ratelimit;

/**
 * What a caller wants to happen when its call would exceed the limit. This is a property of the
 * <em>call path</em>, not of the account: the same Gemini credential is used to embed a backfill (where
 * waiting is free and failing is disruptive) and to embed a search query (where waiting is disruptive
 * and failing is merely a missing answer).
 */
public enum RateLimitMode {

    /**
     * Background work — ingestion, indexing, digests. Wait for the window to reopen, up to
     * {@code app.ratelimit.max-wait-seconds}; beyond that the call is deferred by throwing
     * {@link RateLimitedException}, whose {@code retryAt} the runners write onto the entity or cursor so
     * the work resumes later instead of occupying a scheduler thread for an hour.
     */
    WAIT,

    /**
     * Interactive work on a user's request thread. Never sleeps; throws immediately so the caller can
     * degrade — a rate-limited answer surfaces as {@code answerError} with the search hits intact.
     */
    FAIL_FAST
}
