package io.personalassistant.common.http;

public class OutboundHttpException extends RuntimeException {

    private final int status;
    private final String url;
    private final String bodySnippet;

    public OutboundHttpException(int status, String url, String bodySnippet, String message) {
        super(message);
        this.status = status;
        this.url = url;
        this.bodySnippet = bodySnippet;
    }

    public OutboundHttpException(String url, String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.url = url;
        this.bodySnippet = "";
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

    public boolean isNotFound() {
        return status == 404;
    }
}
