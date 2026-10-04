package io.personalassistant.ingestion.connector.ats.turbohire;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A TurboHire account, the subdomain of {@code <account>.turbohire.co}. It is often not the company name:
 * Ola is {@code olacareers}, and Cleartrip posts under {@code flipkart}.
 */
public record TurboHireSite(String account) {

    private static final Pattern HOST = Pattern.compile(
            "^(?:https?://)?([a-z0-9-]+)\\.turbohire\\.co(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$", Pattern.CASE_INSENSITIVE);

    /** A host, a URL on it, or the bare account; empty for anything else. */
    public static Optional<TurboHireSite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        Matcher host = HOST.matcher(trimmed);
        if (host.matches()) {
            return Optional.of(new TurboHireSite(host.group(1).toLowerCase(Locale.ROOT)));
        }
        return SLUG.matcher(trimmed).matches()
                ? Optional.of(new TurboHireSite(trimmed.toLowerCase(Locale.ROOT)))
                : Optional.empty();
    }

    /** The token endpoint only answers a request that appears to come from the account's own page. */
    public String referer() {
        return "https://" + account + ".turbohire.co/";
    }

    public String jobUrl(String jobId) {
        return "https://" + account + ".turbohire.co/job/publicjobs/" + jobId;
    }

    @Override
    public String toString() {
        return account + ".turbohire.co";
    }
}
