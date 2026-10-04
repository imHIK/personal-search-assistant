package io.personalassistant.ingestion.connector.ats;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** For platforms addressed by a company's own careers domain (Jibe, Zwayam). */
public final class CareersHost {

    private static final Pattern HOST = Pattern.compile("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$", Pattern.CASE_INSENSITIVE);

    /** A pasted job or careers URL: {@code https://careers.docusign.com/careers-home/jobs/30400}. */
    private static final Pattern URL = Pattern.compile(
            "^https?://([a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    private CareersHost() {
    }

    /**
     * The lowercased host of a dotted host or a URL with a scheme; empty for anything else, so a bare company
     * name never costs a request. A schemeless {@code host/path} is left alone, since that is the Workday,
     * Oracle and Eightfold handle shape.
     */
    public static Optional<String> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        Matcher url = URL.matcher(trimmed);
        if (url.matches()) {
            return Optional.of(url.group(1).toLowerCase(Locale.ROOT));
        }
        return HOST.matcher(trimmed).matches() ? Optional.of(trimmed.toLowerCase(Locale.ROOT)) : Optional.empty();
    }
}
