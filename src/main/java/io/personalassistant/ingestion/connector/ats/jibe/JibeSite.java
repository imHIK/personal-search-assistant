package io.personalassistant.ingestion.connector.ats.jibe;

import io.personalassistant.ingestion.connector.ats.CareersHost;
import java.util.Optional;

/**
 * A careers host served by iCIMS Jibe ({@code careers.docusign.com}). The host is the whole address: the job
 * API and the job pages hang off it at fixed paths.
 */
public record JibeSite(String host) {

    public static Optional<JibeSite> parse(String handle) {
        return CareersHost.parse(handle).map(JibeSite::new);
    }

    public String apiRoot() {
        return "https://" + host + "/api/jobs";
    }

    /** Redirects to the site's own job page, whatever path prefix the site uses. */
    public String jobUrl(String slug) {
        return "https://" + host + "/jobs/" + slug;
    }

    @Override
    public String toString() {
        return host;
    }
}
