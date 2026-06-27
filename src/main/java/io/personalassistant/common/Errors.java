package io.personalassistant.common;

/**
 * Small helpers for turning an exception into a compact, persistable summary. The full stack trace
 * still belongs in the log; what we store on a record (cursor, entity, knowledge) is a short
 * {@code ClassName: message} line that's enough to debug from the data without bloating the document.
 */
public final class Errors {

    /** Upper bound on a stored summary so a pathological message can't bloat a document. */
    private static final int MAX_LENGTH = 500;

    private Errors() {
    }

    /**
     * A bounded {@code SimpleClassName: message} summary of {@code e} (just the class name when there
     * is no message, e.g. a bare {@link NullPointerException}). Returns {@code null} for {@code null}.
     */
    public static String summary(Throwable e) {
        if (e == null) {
            return null;
        }
        String summary = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return summary.length() > MAX_LENGTH ? summary.substring(0, MAX_LENGTH) + "…" : summary;
    }
}
