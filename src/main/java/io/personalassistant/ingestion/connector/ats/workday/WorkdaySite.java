package io.personalassistant.ingestion.connector.ats.workday;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tenant/site/wd triple, none of it guessable, so it is read off the careers page.
 *
 * @param tenant e.g. {@code adobe}
 * @param site the career-site slug, e.g. {@code external_experienced}
 * @param wd the pod, e.g. {@code wd5}
 */
public record WorkdaySite(String tenant, String site, String wd) {

    private static final Pattern TRIPLE =
            Pattern.compile("^([\\w.-]+)/([\\w.-]+)/(wd\\d+)$", Pattern.CASE_INSENSITIVE);

    /**
     * A pasted career-site URL: {@code https://adobe.wd5.myworkdayjobs.com/external_experienced}, often with a
     * locale ({@code /en-US/}) ahead of the site.
     */
    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?([\\w-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?([\\w.-]+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** Empty rather than a throw for anything else: that is how a bare company name is told apart. */
    public static Optional<WorkdaySite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        Matcher triple = TRIPLE.matcher(trimmed);
        if (triple.matches()) {
            return Optional.of(new WorkdaySite(triple.group(1), triple.group(2),
                    triple.group(3).toLowerCase(Locale.ROOT)));
        }
        Matcher url = URL.matcher(trimmed);
        if (url.matches()) {
            return Optional.of(new WorkdaySite(url.group(1), url.group(3),
                    url.group(2).toLowerCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    public String apiRoot() {
        return "https://" + tenant + "." + wd + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site;
    }

    @Override
    public String toString() {
        return tenant + "/" + site + "/" + wd;
    }
}
