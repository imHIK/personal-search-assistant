package io.personalassistant.ingestion.connector.ats.eightfold;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Eightfold careers host and the employer domain it serves; the API needs both, and neither is the
 * company name ({@code explore.jobs.netflix.net} / {@code netflix.com}).
 */
public record EightfoldSite(String host, String domain) {

    private static final String DOTTED = "[a-z0-9-]+(?:\\.[a-z0-9-]+)+";

    /** {@code host/domain}, the form stored in {@code inputs.companies}. */
    private static final Pattern PAIR = Pattern.compile("^(" + DOTTED + ")/(" + DOTTED + ")$",
            Pattern.CASE_INSENSITIVE);

    /** A pasted careers URL, which always carries the domain as a query parameter. */
    private static final Pattern URL = Pattern.compile(
            "^https?://(" + DOTTED + ")/[^?#]*\\?(?:[^#]*&)?domain=(" + DOTTED + ")(?:[&#].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** Empty rather than a throw for anything else: that is how a bare company name is told apart. */
    public static Optional<EightfoldSite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        Matcher matcher = URL.matcher(trimmed);
        if (!matcher.matches()) {
            matcher = PAIR.matcher(trimmed);
            if (!matcher.matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(new EightfoldSite(matcher.group(1).toLowerCase(Locale.ROOT),
                matcher.group(2).toLowerCase(Locale.ROOT)));
    }

    @Override
    public String toString() {
        return host + "/" + domain;
    }
}
