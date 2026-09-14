package io.personalassistant.ingestion.connector.ats;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared normalisation for job postings: the per-board adapters differ only in where each value sits
 * in their JSON, so everything <em>derived</em> from those values lives here and is identical across
 * boards. That matters most for {@link #dedupeKey}: the same role listed on two boards only collapses
 * if both sides compute the key the same way.
 *
 * <p>The derivations are deliberately conservative. Seniority, remoteness and compensation are stated
 * in free text by thousands of different companies, so a rule that guesses aggressively produces
 * confident wrong metadata — worse than an absent field, because filters then silently exclude good
 * matches. Every method here returns null/false rather than guessing when the signal is not explicit.
 */
public final class AtsNormalization {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Digits in either Western ({@code 150,000}) or Indian ({@code 15,00,000}) grouping. */
    private static final String DIGITS =
            "(\\d{1,3}(?:,\\d{2})*,\\d{3}|\\d{1,3}(?:,\\d{3})*|\\d+(?:\\.\\d+)?)";

    /**
     * A magnitude suffix, which must end on a word boundary.
     *
     * <p>{@code m} for "million" is deliberately absent. Real postings are full of "6-12 months" and
     * "20-30 minutes", and an {@code m} alternative reads those as six-to-twelve <em>million</em> — a
     * fabricated salary band on a posting that never mentioned pay. Measured on live boards that single
     * alternative produced every false positive the parser had.
     */
    // Wrapped so that appending "?" makes the whole unit optional. Without the (?: ) the "?" would
    // bind to \b alone, quietly making a unit MANDATORY everywhere it appears.
    private static final String UNIT = "(?:(k|l|lac|lakh|lakhs|lpa|cr|crore|crores)\\b)";

    /** Currency markers, longest-first so {@code INR} is not consumed by a shorter alternative. */
    private static final String CURRENCY = "(?:INR|USD|EUR|GBP|Rs\\.?|₹|\\$|£|€)";

    /**
     * Compensation stated as an explicit range — {@code "$150,000 - $190,000"},
     * {@code "₹15,00,000 – ₹25,00,000"}, {@code "18-30 LPA"}.
     *
     * <p>Every match must be <em>anchored</em>, by a leading currency or a trailing Indian magnitude
     * unit. A bare pair of numbers is never enough: "2 - 5 years experience" and "6-12 months" are far
     * more common in a job description than an unmarked salary range, and both would otherwise parse.
     * The trailing-unit branch exists because Indian postings routinely write the amount before the
     * unit and give no currency at all ({@code "CTC: 18-30 LPA"}).
     */
    private static final Pattern COMP_RANGE = Pattern.compile(
            "(?:" + CURRENCY + "\\s*" + DIGITS + "\\s*" + UNIT + "?"           // $150,000 / ₹15L
                    + "|" + DIGITS + "\\s*" + UNIT + "?\\s*(?=(?:-|–|—|to)))"  // 18 (LPA anchored below)
                    + "\\s*(?:-|–|—|to)\\s*" + CURRENCY + "?\\s*" + DIGITS + "\\s*" + UNIT + "?",
            Pattern.CASE_INSENSITIVE);

    /** Which currency a match was stated in, so two postings' numbers are never silently compared. */
    private static final Pattern INR_MARKER =
            Pattern.compile("₹|\\bINR\\b|\\bRs\\.?\\s*\\d|\\bLPA\\b|\\blakhs?\\b|\\bcrores?\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * A monthly figure. Indian postings quote monthly for junior roles often enough that reading one as
     * annual would understate pay by 12x — and there is no safe way to tell without the unit, so a
     * monthly range is skipped rather than converted.
     */
    private static final Pattern PER_MONTH =
            Pattern.compile("(per\\s*month|/\\s*month|\\bp\\.?m\\.?\\b|monthly)", Pattern.CASE_INSENSITIVE);

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
     * Compensation parsed from an explicit range, or null when the posting states none.
     *
     * <p><strong>Expect null far more often than not.</strong> Pay-transparency law makes US postings
     * publish a band; most other markets do not. Measured against these boards, Indian postings state a
     * figure in roughly 0% of cases — so a filter on {@code compMin} silently excludes nearly every
     * Indian role rather than narrowing it. See {@code docs/job-discovery.md}.
     *
     * @param descriptionText plain text of the posting
     * @return the range, or null when none is stated, the units are ambiguous, or the figure is monthly
     */
    public static CompRange compRange(String descriptionText) {
        if (descriptionText == null || descriptionText.isBlank()) {
            return null;
        }
        Matcher m = COMP_RANGE.matcher(descriptionText);
        if (!m.find()) {
            return null;
        }
        // Groups come in (amount, suffix) pairs: 1-2 lead-currency form, 3-4 trailing-unit form, 5-6
        // the upper bound. Exactly one of the first two forms matched.
        String lowDigits = m.group(1) != null ? m.group(1) : m.group(3);
        String lowSuffix = m.group(1) != null ? m.group(2) : m.group(4);
        String highDigits = m.group(5);
        String highSuffix = m.group(6);

        // Anchor check. The pattern alone can still match a bare "200,000 - 300,000" through its
        // second branch, which is exactly the unmarked range that must not be read as pay.
        boolean hasCurrency = CURRENCY_IN_MATCH.matcher(m.group()).find();
        boolean hasUnit = (lowSuffix != null && !lowSuffix.isBlank())
                || (highSuffix != null && !highSuffix.isBlank());
        if (!hasCurrency && !hasUnit) {
            return null;
        }

        String currency = currencyOf(m.group(), descriptionText);
        // A suffix on either side applies to both: "18-30 LPA" states the unit once, at the end.
        String unit = highSuffix != null && !highSuffix.isBlank() ? highSuffix : lowSuffix;

        Long low = parseAmount(lowDigits, lowSuffix != null && !lowSuffix.isBlank() ? lowSuffix : unit);
        Long high = parseAmount(highDigits, unit);
        if (low == null || high == null || low > high) {
            return null;
        }
        if (PER_MONTH.matcher(window(descriptionText, m.start(), m.end())).find()) {
            return null;
        }
        // Below a plausible annual salary the number is something else — an hourly rate, a headcount, a
        // percentage. The floor has to be per-currency: 50,000 is a real INR monthly figure and an
        // implausible USD annual one, so a single threshold is wrong for one of them.
        long floor = "INR".equals(currency) ? 100_000L : 10_000L;
        return low < floor ? null : new CompRange(low, high, currency);
    }

    private static final Pattern CURRENCY_IN_MATCH = Pattern.compile(CURRENCY, Pattern.CASE_INSENSITIVE);

    /** The text immediately around a match, where a "per month" qualifier would sit. */
    private static String window(String text, int start, int end) {
        return text.substring(Math.max(0, start - 40), Math.min(text.length(), end + 40));
    }

    /**
     * The currency a range was stated in. Checks the match itself first, then its immediate
     * surroundings — an Indian posting often writes "CTC: 18-30 LPA" with the unit carrying the
     * currency and no symbol anywhere.
     */
    private static String currencyOf(String match, String full) {
        if (INR_MARKER.matcher(match).find()) {
            return "INR";
        }
        if (match.contains("$") || match.toUpperCase(Locale.ROOT).contains("USD")) {
            return "USD";
        }
        if (match.contains("£") || match.toUpperCase(Locale.ROOT).contains("GBP")) {
            return "GBP";
        }
        if (match.contains("€") || match.toUpperCase(Locale.ROOT).contains("EUR")) {
            return "EUR";
        }
        int at = full.indexOf(match);
        String around = at < 0 ? full : window(full, at, at + match.length());
        return INR_MARKER.matcher(around).find() ? "INR" : null;
    }

    /**
     * A parsed pay band, always carrying the currency it was stated in.
     *
     * <p>The currency is not optional detail. {@code compMin} and {@code compMax} are plain numbers in
     * the index, so a corpus mixing INR and USD makes {@code compMin >= 150000} mean two very different
     * things at once — a filter that looks precise and is not. Callers must record
     * {@code compCurrency} alongside, and a range filter is only meaningful next to a currency term.
     *
     * @param currency ISO-ish code, or null when the posting gave a magnitude but no currency
     */
    public record CompRange(long min, long max, String currency) {}

    /**
     * Digits plus an optional magnitude unit. Commas are stripped before parsing, which is what makes
     * Indian grouping safe here — {@code 15,00,000} and {@code 1500000} become the same number once the
     * pattern has already established where the digits end.
     */
    private static Long parseAmount(String digits, String unit) {
        if (digits == null) {
            return null;
        }
        String v = digits.replace(",", "").trim();
        try {
            double n = Double.parseDouble(v);
            return Math.round(n * multiplier(unit));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code L}/{@code lakh}/{@code LPA} = 10^5, {@code cr}/{@code crore} = 10^7 — the Indian scale. */
    private static long multiplier(String unit) {
        if (unit == null || unit.isBlank()) {
            return 1;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "l", "lac", "lakh", "lakhs", "lpa" -> 100_000L;
            case "cr", "crore", "crores" -> 10_000_000L;
            default -> 1L;
        };
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
