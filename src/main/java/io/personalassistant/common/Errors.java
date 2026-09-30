package io.personalassistant.common;

public final class Errors {

    private static final int MAX_LENGTH = 500;

    private Errors() {
    }

    public static String summary(Throwable e) {
        if (e == null) {
            return null;
        }
        String summary = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return summary.length() > MAX_LENGTH ? summary.substring(0, MAX_LENGTH) + "…" : summary;
    }
}
