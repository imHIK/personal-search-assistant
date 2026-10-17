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
 * @param frontEnd the company's own job-page prefix (a Phenom site over Workday), or null; postings link there
 *     plus the {@code jobReqId}, since that site has its own candidate login and is where referrals point
 */
public record WorkdaySite(String tenant, String site, String wd, String frontEnd) {

    private static final Pattern TRIPLE =
            Pattern.compile("^([\\w.-]+)/([\\w.-]+)/(wd\\d+)$", Pattern.CASE_INSENSITIVE);

    /**
     * A pasted career-site URL: {@code https://adobe.wd5.myworkdayjobs.com/external_experienced}, often with a
     * locale ({@code /en-US/}) ahead of the site.
     */
    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?([\\w-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?([\\w.-]+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FRONT_END = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    public WorkdaySite(String tenant, String site, String wd) {
        this(tenant, site, wd, null);
    }

    /**
     * Empty rather than a throw for anything else: that is how a bare company name is told apart. A handle may
     * end in {@code @<front-end prefix>}: {@code cisco/Cisco_Careers/wd5@https://careers.cisco.com/global/en/job/}.
     */
    public static Optional<WorkdaySite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        int at = trimmed.indexOf('@');
        if (at >= 0) {
            String frontEnd = trimmed.substring(at + 1).trim();
            if (!FRONT_END.matcher(frontEnd).matches()) {
                return Optional.empty();
            }
            return parse(trimmed.substring(0, at)).map(s -> new WorkdaySite(s.tenant(), s.site(), s.wd(), frontEnd));
        }
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

    public Optional<String> jobUrl(String reqId) {
        return frontEnd == null || reqId == null || reqId.isBlank()
                ? Optional.empty()
                : Optional.of(frontEnd + reqId);
    }

    /** The bare triple: it is the {@code board} metadata, and a front end does not make it another board. */
    @Override
    public String toString() {
        return tenant + "/" + site + "/" + wd;
    }
}
