package io.personalassistant.ingestion.connector.ats;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Derivations shared by every board, so the same role on two boards computes the same dedupeKey.
 * Conservative: null or false rather than a guess when the signal is not explicit.
 */
public final class AtsNormalization {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Hybrid is excluded: on-site some days is not remote. */
    private static final Pattern REMOTE =
            Pattern.compile("\\b(fully[ -]?remote|remote[ -]?first|remote)\\b", Pattern.CASE_INSENSITIVE);

    private AtsNormalization() {
    }

    /**
     * Company, title and location: what every board states and a reposting preserves. Null when company or
     * title is missing, since a key of blanks would group every such posting.
     */
    public static String dedupeKey(String company, String title, String location) {
        String c = normalizeToken(company);
        String t = normalizeToken(title);
        if (c.isEmpty() || t.isEmpty()) {
            return null;
        }
        return c + "|" + t + "|" + normalizeToken(location);
    }

    /**
     * The user's label wins over a handle: Oracle's CX_1, a Workday tenant or a board token is not a name,
     * and a handle in metadata.company breaks dedupeKey across platforms.
     */
    public static String company(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label.trim();
    }

    /**
     * The company is indexed, so a relabelled posting counts as changed. Extended only when the label changed
     * the company, so unlabelled boards keep their checksums.
     */
    public static String withCompany(String checksum, String company, String unlabelled) {
        return company == null || company.equals(unlabelled) ? checksum : checksum + ";co:" + company;
    }

    public static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.ROOT).trim();
        return NON_ALNUM.matcher(lowered).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    /** A crude tag strip for scanning prose; indexed text goes through the HTML parser. */
    public static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String stripped = TAG.matcher(html).replaceAll(" ");
        stripped = stripped.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * The location field first, where a board states it; the description only when the location is silent.
     */
    public static boolean isRemote(String location, String descriptionText) {
        if (location != null && REMOTE.matcher(location).find()) {
            return true;
        }
        // Only the opening is scanned: "remote" deep in a benefits section describes the culture, not the
        // role.
        String head = descriptionText == null ? ""
                : descriptionText.substring(0, Math.min(descriptionText.length(), 400));
        return REMOTE.matcher(head).find();
    }

    /** From the title only; null when it carries no explicit marker, which is common and correct. */
    public static String seniority(String title) {
        if (title == null) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("intern") && !t.contains("internal")) {
            return "INTERN";
        }
        if (t.contains("principal") || t.contains("distinguished") || t.contains("fellow")) {
            return "PRINCIPAL";
        }
        if (t.contains("staff")) {
            return "STAFF";
        }
        if (t.contains("director") || t.contains("head of") || t.contains("vp ")
                || t.contains("vice president")) {
            return "LEADERSHIP";
        }
        if (t.contains("manager") || t.contains("lead ") || t.endsWith(" lead")) {
            return "LEAD";
        }
        if (t.contains("senior") || t.contains("sr.") || t.contains("sr ")) {
            return "SENIOR";
        }
        if (t.contains("junior") || t.contains("jr.") || t.contains("graduate")
                || t.contains("entry level") || t.contains("entry-level")) {
            return "JUNIOR";
        }
        return null;
    }

    /**
     * Shared, so a platform's early filter and the connector's cannot disagree. A blank location matches:
     * boards leave it empty often.
     *
     * @param terms already lowercased; empty keeps everything
     */
    public static boolean matchesLocation(String location, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        String trimmed = location == null ? "" : location.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lowered.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For boards whose timestamp cannot be the change signal: Greenhouse's updated_at moves in bulk (178 of
     * GitLab's 227 postings share one to the second), and Ashby publishes none. Title and location are
     * covered too, since they are indexed. String.hashCode suffices: a stamp is only compared with the same
     * posting's previous one.
     */
    public static String changeStamp(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "" : part).append('\u0000');
        }
        return Integer.toHexString(joined.toString().hashCode());
    }

    /** Lenient: a bad date must not fail a page. */
    public static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Epoch millis, as Lever states its dates. */
    public static Instant instantOrNull(Long epochMillis) {
        return epochMillis == null || epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }
}
