package io.personalassistant.ingestion.connector.ats.workday;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Workday career site, identified by a {@code tenant/site/wd} triple.
 *
 * <p><strong>None of the three parts is guessable.</strong> Probing 22 companies that certainly use
 * Workday, 13 failed purely because the site name or {@code wd} number was wrong — Workday gives each
 * customer an arbitrary site slug on one of several numbered pods. That is why this platform, alone
 * among those supported, cannot resolve a bare company name: the triple has to be read off the
 * company's careers page.
 *
 * @param tenant the customer, e.g. {@code adobe}
 * @param site   the career-site slug, e.g. {@code external_experienced}
 * @param wd     the pod, e.g. {@code wd5}
 */
public record WorkdaySite(String tenant, String site, String wd) {

    /** {@code tenant/site/wd}, in any order-independent-looking form a user might paste. */
    private static final Pattern TRIPLE =
            Pattern.compile("^([\\w.-]+)/([\\w.-]+)/(wd\\d+)$", Pattern.CASE_INSENSITIVE);

    /** A pasted career-site URL: {@code https://adobe.wd5.myworkdayjobs.com/external_experienced}. */
    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?([\\w-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[\\w-]+/)??([\\w.-]+)/?.*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a handle, accepting either the triple or a pasted career-site URL.
     *
     * <p>Returns empty rather than throwing for anything else, because this is what tells the connector
     * that a bare company name is not a Workday site — and resolution asks that of every name.
     */
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

    /** {@code https://{tenant}.{wd}.myworkdayjobs.com/wday/cxs/{tenant}/{site}} — the API root. */
    public String apiRoot() {
        return "https://" + tenant + "." + wd + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site;
    }

    @Override
    public String toString() {
        return tenant + "/" + site + "/" + wd;
    }
}
