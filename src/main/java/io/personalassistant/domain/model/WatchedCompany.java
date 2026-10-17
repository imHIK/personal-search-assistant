package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.Locale;

/**
 * A company whose job board no connector reaches, kept so its careers site can be checked by hand.
 *
 * @param domain the company's own site, for its logo
 * @param tier null only on a row stored before tiers existed, until an import fills it
 * @param lookupHandle set when the company is on a supported board that cannot be verified yet (no public
 *                     postings); the console offers the job-board lookup for it
 * @param lastCheckedAt null until first marked checked
 */
public record WatchedCompany(
        String id,
        String name,
        String domain,
        String careersUrl,
        String platform,
        CompanyTier tier,
        String reason,
        String note,
        String lookupHandle,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** Uniqueness is on this, so "Zepto" and "zepto " are one company. */
    public static String nameKey(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public WatchedCompany withEdits(String newName, String newDomain, String newCareersUrl, String newPlatform,
                                    CompanyTier newTier, String newReason, String newNote,
                                    String newLookupHandle, Instant at) {
        return new WatchedCompany(id, newName, newDomain, newCareersUrl, newPlatform, newTier, newReason, newNote,
                newLookupHandle, lastCheckedAt, createdAt, at);
    }

    public WatchedCompany withLastCheckedAt(Instant checkedAt) {
        return new WatchedCompany(id, name, domain, careersUrl, platform, tier, reason, note, lookupHandle,
                checkedAt, createdAt, updatedAt);
    }

    public WatchedCompany withTier(CompanyTier newTier) {
        return new WatchedCompany(id, name, domain, careersUrl, platform, newTier, reason, note, lookupHandle,
                lastCheckedAt, createdAt, updatedAt);
    }
}
