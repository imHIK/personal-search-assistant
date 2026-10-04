package io.personalassistant.ingestion.connector.ats.freshteam;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Freshteam account, {@code <sub>.freshteam.com}; the subdomain is often not the company name. */
public record FreshteamSite(String host) {

    private static final Pattern HOST = Pattern.compile(
            "^(?:https?://)?([a-z0-9-]+\\.freshteam\\.com)(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    /** Empty rather than a throw for anything else: that is how a bare company name is told apart. */
    public static Optional<FreshteamSite> parse(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        Matcher host = HOST.matcher(handle.trim());
        return host.matches()
                ? Optional.of(new FreshteamSite(host.group(1).toLowerCase(Locale.ROOT)))
                : Optional.empty();
    }

    public String jobsUrl() {
        return "https://" + host + "/hire/widgets/jobs.json";
    }

    @Override
    public String toString() {
        return host;
    }
}
