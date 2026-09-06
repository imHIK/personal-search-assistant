package io.personalassistant.ingestion.connector.ats.oraclehcm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Oracle Recruiting Cloud career site, identified by a {@code host/siteNumber} pair.
 *
 * <p>Like Workday, and unlike the four bare-name platforms, <strong>neither part is guessable</strong>.
 * The host is a per-customer Fusion pod ({@code eofe.fa.us2.oraclecloud.com},
 * {@code jpmc.fa.oraclecloud.com}, {@code hcbt.fa.em2.oraclecloud.com} — the prefix is not the company
 * name and the region segment is sometimes absent), and the site number is an arbitrary slug
 * ({@code CX_1}, {@code CX_1001}, {@code BNY-Careers}). Both have to be read off the careers URL, which
 * is why {@link #parse} accepts a pasted one.
 *
 * <p>The vanity-domain case is the reason this is a pair rather than a single string: employers front
 * the pod with their own hostname ({@code jobs.akamai.com}, {@code careers.americanexpress.com}) and
 * those hostnames serve the UI but <em>not</em> the REST API, so the underlying pod host is the part
 * that matters and a vanity URL cannot be used as-is.
 *
 * @param host the Fusion host serving the REST API, without scheme
 * @param site the {@code siteNumber} of the career site on that host
 */
public record OracleHcmSite(String host, String site) {

    /** {@code host/site}, the form stored in {@code inputs.companies}. */
    private static final Pattern PAIR = Pattern.compile(
            "^([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.oraclecloud\\.com)/([A-Za-z0-9_-]+)$",
            Pattern.CASE_INSENSITIVE);

    /** A pasted career-site URL: {@code https://host/hcmUI/CandidateExperience/en/sites/CX_1/jobs}. */
    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.oraclecloud\\.com)"
                    + "/hcmUI/CandidateExperience/[\\w-]+/sites/([A-Za-z0-9_-]+).*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a handle, accepting either the pair or a pasted career-site URL.
     *
     * <p>Returns empty rather than throwing for anything else — that is what tells the connector a bare
     * company name is not an Oracle HCM site, and resolution asks that of every name.
     */
    public static Optional<OracleHcmSite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        Matcher url = URL.matcher(trimmed);
        if (url.matches()) {
            return Optional.of(new OracleHcmSite(url.group(1).toLowerCase(), url.group(2)));
        }
        Matcher pair = PAIR.matcher(trimmed);
        if (pair.matches()) {
            return Optional.of(new OracleHcmSite(pair.group(1).toLowerCase(), pair.group(2)));
        }
        return Optional.empty();
    }

    /** {@code https://{host}/hcmRestApi/resources/latest} — the API root. */
    public String apiRoot() {
        return "https://" + host + "/hcmRestApi/resources/latest";
    }

    /** The public page for one requisition, used as the posting's {@code uri}. */
    public String jobUrl(String id) {
        return "https://" + host + "/hcmUI/CandidateExperience/en/sites/" + site + "/job/" + id;
    }

    @Override
    public String toString() {
        return host + "/" + site;
    }
}
