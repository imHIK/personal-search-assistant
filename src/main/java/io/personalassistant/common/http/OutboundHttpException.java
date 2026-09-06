package io.personalassistant.common.http;

/**
 * A failed outbound call, in provider-neutral terms. The per-area facades translate it into the
 * exceptions their connectors already branch on ({@code AtsApiException}, {@code GoogleApiException}),
 * which is why the shared transport can stay ignorant of who called it.
 */
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

    /** HTTP status, or {@code 0} when the call failed before a response arrived. */
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
