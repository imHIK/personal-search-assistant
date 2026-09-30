package io.personalassistant.ingestion.connector.ats.oraclehcm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A host/siteNumber pair, neither guessable: the host is a per-customer Fusion pod and the site number an
 * arbitrary slug, so both are read off the careers URL. Vanity domains serve the UI but not the REST API, so
 * the pod host is what matters.
 *
 * @param host the Fusion host serving the REST API, without scheme
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

    /** Empty rather than a throw for anything else: that is how a bare company name is told apart. */
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

    public String apiRoot() {
        return "https://" + host + "/hcmRestApi/resources/latest";
    }

    public String jobUrl(String id) {
        return "https://" + host + "/hcmUI/CandidateExperience/en/sites/" + site + "/job/" + id;
    }

    @Override
    public String toString() {
        return host + "/" + site;
    }
}
