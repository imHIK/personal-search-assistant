package io.personalassistant.ingestion.connector.ats.keka;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Keka careers portal, {@code <tenant>.keka.com}. The tenant is often not the company name. */
public record KekaSite(String host) {

    private static final Pattern HOST = Pattern.compile(
            "^(?:https?://)?([a-z0-9-]+\\.keka\\.com)(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    /** Empty rather than a throw for anything else: that is how a bare company name is told apart. */
    public static Optional<KekaSite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        Matcher host = HOST.matcher(handle.trim());
        return host.matches()
                ? Optional.of(new KekaSite(host.group(1).toLowerCase(Locale.ROOT)))
                : Optional.empty();
    }

    public String careersPage() {
        return "https://" + host + "/careers/";
    }

    public String jobsUrl(String boardId) {
        return "https://" + host + "/careers/api/embedjobs/default/active/" + boardId;
    }

    public String jobUrl(String id) {
        return "https://" + host + "/careers/jobdetails/" + id;
    }

    @Override
    public String toString() {
        return host;
    }
}
