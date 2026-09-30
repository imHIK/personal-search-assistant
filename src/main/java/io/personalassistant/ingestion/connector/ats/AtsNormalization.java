package io.personalassistant.ingestion.connector.ats;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared normalisation for job postings: the per-board adapters differ only in where each value sits
 * in their JSON, so everything <em>derived</em> from those values lives here and is identical across
 * boards. That matters most for {@link #dedupeKey}: the same role listed on two boards only collapses
 * if both sides compute the key the same way.
 *
 * <p>The derivations are deliberately conservative. Seniority and remoteness are stated in free text by
 * thousands of different companies, so a rule that guesses aggressively produces confident wrong metadata — worse than an absent field, because filters then silently exclude good
 * matches. Every method here returns null/false rather than guessing when the signal is not explicit.
 */
public final class AtsNormalization {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Explicit remote markers. "Hybrid" is excluded — it means on-site some days, which is not remote. */
    private static final Pattern REMOTE =
            Pattern.compile("\\b(fully[ -]?remote|remote[ -]?first|remote)\\b", Pattern.CASE_INSENSITIVE);

    private AtsNormalization() {
    }

    /**
     * The cross-source grouping key: the same role on two boards should produce the same string.
     * Company, title and location are the three things every board states and that a reposting
     * preserves; anything more volatile (req id, posting date, description wording) would split
     * groups that ought to collapse.
     *
     * <p>Returns null when company or title is missing — a key built from blanks would group every
     * such posting together, which is worse than not grouping them at all.
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
     * The name a posting is filed under: the user's label for the company when there is one, otherwise
     * what the platform can say for itself.
     *
     * <p>The label exists because a handle is not a name. Oracle's site number ({@code CX_1}), a Workday
     * tenant ({@code ghr} is Bank of America) and a board token ({@code digitalocean98}) all used to land
     * in {@code metadata.company}, where they are shown to the user, handed to the answer prompt and
     * built into {@link #dedupeKey} — which then never matched the same role on another platform.
     * Neither Oracle nor Workday publishes a usable employer name to fall back on: Oracle's site name
     * is "Candidate Experience site" on Kotak's pod, and Workday's {@code hiringOrganization} is a legal
     * entity prefixed with a tax id.
     */
    public static String company(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label.trim();
    }

    /**
     * {@code checksum}, extended with the company only when a label changed it.
     *
     * <p>Invariant 3: the company is indexed metadata, so a posting whose company changed has changed,
     * and without this every posting stored under a handle would be skipped forever. Extending only
     * when {@code company} differs from what the platform would have filed it under keeps unlabelled
     * boards' checksums exactly as they were — no re-embedding for a change that changed nothing.
     */
    public static String withCompany(String checksum, String company, String unlabelled) {
        return company == null || company.equals(unlabelled) ? checksum : checksum + ";co:" + company;
    }

    /** Lowercase, strip punctuation, collapse separators — the canonical form used inside a dedupe key. */
    public static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.ROOT).trim();
        return NON_ALNUM.matcher(lowered).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    /** Crude tag strip, for scanning a description's prose. Never used for indexed text — that goes through the HTML parser. */
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
     * Whether the posting says it is remote. Checks the location field first because that is where a
     * board states it structurally; the description is a weaker signal and is only consulted when the
     * location is silent.
     */
    public static boolean isRemote(String location, String descriptionText) {
        if (location != null && REMOTE.matcher(location).find()) {
            return true;
        }
        // Only the opening of a description is worth scanning: "remote" deep in a benefits section is
        // usually describing the company culture, not this role.
        String head = descriptionText == null ? ""
                : descriptionText.substring(0, Math.min(descriptionText.length(), 400));
        return REMOTE.matcher(head).find();
    }

    /**
     * Seniority band inferred from the title only — the one place a board states it consistently.
     * Returns null when the title carries no explicit marker, which is common and correct: an
     * unlabelled "Software Engineer" is genuinely ambiguous.
     */
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
     * Whether a posting's location matches one of {@code terms}, case-insensitively.
     *
     * <p>Shared so that a platform filtering early (to avoid per-posting work) and the connector
     * filtering authoritatively afterwards cannot disagree — a mismatch there would drop postings the
     * connector would have kept, invisibly.
     *
     * <p><strong>A blank location matches.</strong> Boards leave the field empty often enough that
     * dropping those would lose real roles on a missing value, and nothing distinguishes an irrelevant
     * location from an unstated one.
     *
     * @param terms match terms, already lowercased; empty keeps everything
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
     * A stamp over everything a posting contributes to the index, for boards whose published
     * timestamp cannot be used as the change signal (invariant 3).
     *
     * <p>Two different failures made this necessary, and they point in opposite directions:
     *
     * <ul>
     *   <li><strong>Greenhouse's {@code updated_at} moves in bulk.</strong> Measured live: 178 of
     *       GitLab's 227 postings share one {@code updated_at} to the second, and 233 of Okta's 313 do
     *       — no recruiter edits 178 descriptions in the same second. Trusting it re-embeds most of a
     *       board at once for a change that never touched the text.</li>
     *   <li><strong>Ashby publishes no {@code updatedAt} at all</strong> — the field is
     *       {@code publishedAt}. Reading the absent one produced a constant, so an edited Ashby posting
     *       was never re-indexed.</li>
     * </ul>
     *
     * <p>Covers title and location as well as the body, because those are indexed too: a role
     * relocated from Bengaluru to Dublin has changed for a reader even if its description has not.
     * Nulls are folded in as empty so the stamp stays stable when an optional field is absent.
     *
     * <p>{@code String.hashCode} is deliberate. This is compared only against the previous stamp for
     * <em>the same posting</em>, so the question is whether an edit collides with its own predecessor,
     * not whether any two postings collide — and it keeps the stamp short enough to read in a document.
     */
    public static String changeStamp(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "" : part).append('\u0000');
        }
        return Integer.toHexString(joined.toString().hashCode());
    }

    /** Parse an ISO-8601 timestamp leniently; boards vary and a bad date must not fail a whole page. */
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

    /** Epoch-millis timestamps (Lever states its dates this way). */
    public static Instant instantOrNull(Long epochMillis) {
        return epochMillis == null || epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }
}
