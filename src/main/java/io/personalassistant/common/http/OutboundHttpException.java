package io.personalassistant.common.http;

import java.time.Instant;

public class OutboundHttpException extends RuntimeException {

    private final int status;
    private final String url;
    private final String bodySnippet;
    private final Instant retryAt;

    public OutboundHttpException(int status, String url, String bodySnippet, String message) {
        this(status, url, bodySnippet, message, null);
    }

    public OutboundHttpException(int status, String url, String bodySnippet, String message, Instant retryAt) {
        super(message);
        this.status = status;
        this.url = url;
        this.bodySnippet = bodySnippet;
        this.retryAt = retryAt;
    }

    public OutboundHttpException(String url, String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.url = url;
        this.bodySnippet = "";
        this.retryAt = null;
    }

    /** 0 when the call failed before a response arrived. */
    public int status() {
        return status;
    }

    public String url() {
        return url;
    }

    public String bodySnippet() {
        return bodySnippet;
    }

    /** Set only on a 429: when the call's quota was paused until. */
    public Instant retryAt() {
        return retryAt;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
