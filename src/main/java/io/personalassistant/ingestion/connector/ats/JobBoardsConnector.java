package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Company job boards across every supported applicant-tracking platform.
 *
 * <p><strong>A company is an iterable.</strong> The user lists companies they want to watch and
 * nothing else; which ATS each one happens to use is worked out here. That matters because the
 * platform is not a property of the job hunt — Meesho and CRED are on Lever, Databricks on Greenhouse,
 * Tekion on Ashby, and requiring someone to know that before they can watch a company makes widening
 * the net needlessly expensive.
 *
 * <p>Resolution happens once, in {@link #discover}, and the answer is carried on the
 * {@link SourceIterable}'s attributes. The framework snapshots those onto the {@code Cursor}, so
 * {@link #grab} reads the platform straight back and never re-probes — which is exactly what the
 * attributes mechanism exists for (see the {@code Cursor} javadoc on avoiding a re-{@code discover}
 * per lease).
 *
 * <h2>Snapshot-shaped</h2>
 * Every supported platform returns the entire current board in one request — no {@code updated_after},
 * no continuation token, no meaningful pagination — so this implements {@link SourceConnector} directly
 * rather than extending a paging base, as {@code LocalFsConnector} does. The seed
 * {@link io.personalassistant.ingestion.connector.TimeWindow} is ignored and every grab returns one
 * page with {@code hasMore=false}.
 *
 * <p>That is not as wasteful as it sounds: change detection in {@code IngestionRunner.persistItem}
 * skips any posting whose checksum is unchanged and already {@code INDEXED}, so a poll costs one HTTP
 * call plus N cheap Mongo lookups and re-indexes only what moved.
 *
 * <h2>Forward-only, and disappearance</h2>
 * Backward cursors walk history below the anchor, and a job board has none worth walking — a posting
 * old enough to sit below the anchor is filled or withdrawn. A closed posting simply stops appearing
 * in the snapshot; boards send no tombstone, which is why this connector opts into a retention window.
 * See {@code docs/knowledge-lifecycle.md}.
 */
@ApplicationScoped
public class JobBoardsConnector implements SourceConnector {

    private static final Logger LOG = Logger.getLogger(JobBoardsConnector.class.getName());

    /** {@code inputs} key holding the companies to watch. */
    public static final String COMPANIES_INPUT = "companies";

    /**
     * {@code inputs} key holding location match terms. Empty or absent keeps every posting.
     *
     * <p>The highest-leverage setting here. A board is global: Databricks carries 857 postings to
     * surface 92 Indian ones, so without this roughly nine tenths of everything ingested is parsed,
     * chunked and <strong>embedded</strong> for a country the user will never apply to — and embeddings
     * are the scarcest resource in the pipeline.
     */
    public static final String LOCATIONS_INPUT = "locations";

    /** Iterable attributes carrying the resolved platform and handle through to {@link #grab}. */
    public static final String PLATFORM_ATTRIBUTE = "platform";
    public static final String HANDLE_ATTRIBUTE = "handle";

    /** Cursor field: when the last complete snapshot was taken. Observability only — grabs are stateless. */
    private static final String POS_SNAPSHOT_AT = "snapshotAtMillis";

    /**
     * Poll cadence. Boards change on a human timescale (a recruiter publishing a role), so polling far
     * more often than this only burns requests without surfacing anything sooner.
     */
    private static final Duration POLL_INTERVAL = Duration.ofHours(3);

    /**
     * Retention window. Comfortably longer than the poll interval — an item is re-created by the next
     * walk if it still exists at the source, so a short window would just churn re-embeddings — and
     * short enough that a filled role does not linger in search for months.
     */
    private static final Duration RETENTION = Duration.ofDays(14);

    private final Instance<BoardPlatform> platforms;

    @Inject
    public JobBoardsConnector(Instance<BoardPlatform> platforms) {
        this.platforms = platforms;
    }

    @Override
    public SourceType type() {
        return SourceType.JOB_BOARDS;
    }

    @Override
    public Set<CursorDirection> supportedDirections() {
        return EnumSet.of(CursorDirection.FORWARD);
    }

    @Override
    public boolean hasDynamicIterables() {
        // The company list is user-supplied, not discovered, so it only changes on an explicit edit —
        // which the edit path already re-runs discover() for.
        return false;
    }

    @Override
    public SyncSchedule defaultSchedule() {
        return SyncSchedule.ofInterval(POLL_INTERVAL);
    }

    @Override
    public Optional<Duration> defaultRetention() {
        return Optional.of(RETENTION);
    }

    /**
     * Covers {@code locations} only.
     *
     * <p>{@code companies} is deliberately excluded: each company is its own iterable, so adding or
     * removing one is a discovery-set change handled by discover-reconcile, and including it would
     * reset every surviving company's cursor on an unrelated edit.
     *
     * <p>{@code locations} is the opposite case — it moves the membership boundary <em>inside</em> each
     * iterable, exactly like {@code GmailConnector}'s query. It must be in the signature: widening the
     * filter has to re-walk the boards, or postings that now match are silently never picked up,
     * because change detection alone never revisits a board it has already seen.
     */
    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        return String.join(",", locations(inputs));
    }

    @Override
    public void verify(Knowledge knowledge) {
        List<String> companies = companies(knowledge);
        if (companies.isEmpty()) {
            throw new IllegalArgumentException(
                    "inputs." + COMPANIES_INPUT + " must list at least one company");
        }
        List<String> unresolved = new ArrayList<>();
        for (String company : companies) {
            if (resolve(company).isEmpty()) {
                unresolved.add(company);
            }
        }
        if (unresolved.size() == companies.size()) {
            // Every single one failed: almost certainly a typo or an outage rather than a genuine
            // "none of these use a supported platform", so refuse rather than activate a dead knowledge.
            throw new IllegalArgumentException("No supported job board found for any of: "
                    + String.join(", ", unresolved) + ". Supported platforms: " + platformIds());
        }
        if (!unresolved.isEmpty()) {
            // A partial miss is normal — plenty of companies are on none of these — so it is reported
            // and the rest proceed.
            LOG.warning("No supported job board for: " + String.join(", ", unresolved));
        }
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        List<SourceIterable> iterables = new ArrayList<>();
        for (String company : companies(knowledge)) {
            resolve(company).ifPresent(r -> iterables.add(new SourceIterable(
                    company,
                    company + " (" + r.platform().id() + ")",
                    Map.of(PLATFORM_ATTRIBUTE, r.platform().id(), HANDLE_ATTRIBUTE, r.handle()))));
        }
        return iterables;
    }

    @Override
    public GrabResult grab(GrabContext context) {
        Resolved resolved = fromAttributes(context)
                // Cursors created before resolution was stored, or a platform bean that has since gone
                // away: re-resolve rather than fail the walk.
                .or(() -> resolve(context.iterableId()))
                .orElseThrow(() -> new AtsApiException(
                        "No supported job board for '" + context.iterableId() + "'"));

        // Filtered here rather than inside the platform: none of these APIs takes a location parameter,
        // so the whole board arrives either way and the saving is entirely downstream — the upsert,
        // parse, chunk and embed that every surviving item pays for.
        List<String> terms = locations(context.knowledge());
        List<RawItem> items = retainMatching(resolved.platform().fetch(resolved.handle(), terms), terms);

        CursorPosition position = context.cursor().toBuilder()
                .put(POS_SNAPSHOT_AT, Instant.now().toEpochMilli())
                .build();
        // One snapshot is the whole board, so there is never a second page. The runner maps
        // hasMore=false on a forward cursor to IDLE: nothing more to do until the schedule re-arms.
        return new GrabResult(items, position, false);
    }

    /**
     * Report what each candidate company resolves to, without creating anything.
     *
     * <p>Exists because the watchlist is the whole product here: reach is a function of how many
     * companies are named, and finding out whether a company is reachable used to mean creating a
     * knowledge and seeing what happened. This answers it for fifty names at once.
     *
     * <p>The posting count comes free — every platform's existence check already carries one — and it
     * is what tells you whether a company is worth adding. What is deliberately <em>not</em> reported is
     * how many of those postings match a location filter: that would need the full board fetched, which
     * on SmartRecruiters and Workday is one request per posting.
     */
    public List<CompanyLookup> lookup(List<String> companies) {
        List<CompanyLookup> out = new ArrayList<>();
        for (String company : companies) {
            if (company == null || company.isBlank()) {
                continue;
            }
            String trimmed = company.trim();
            out.add(resolve(trimmed)
                    .map(r -> new CompanyLookup(trimmed, r.platform().id(), r.handle(),
                            r.platform().countPostings(r.handle()).orElse(0)))
                    .orElseGet(() -> new CompanyLookup(trimmed, null, null, 0)));
        }
        return List.copyOf(out);
    }

    /**
     * What one candidate company resolved to.
     *
     * @param platform the hosting platform, or null when no supported platform has a board for it —
     *                 which is a normal answer, not an error
     * @param postings how many postings that board holds, before any location filter
     */
    public record CompanyLookup(String company, String platform, String handle, int postings) {}

    // ---- resolution --------------------------------------------------------------------------

    /** A company resolved to the platform that hosts it. */
    private record Resolved(BoardPlatform platform, String handle) {}

    private Optional<Resolved> fromAttributes(GrabContext context) {
        Object platformId = context.attributes().get(PLATFORM_ATTRIBUTE);
        Object handle = context.attributes().get(HANDLE_ATTRIBUTE);
        if (!(platformId instanceof String p) || !(handle instanceof String h) || h.isBlank()) {
            return Optional.empty();
        }
        return platform(p).map(found -> new Resolved(found, h));
    }

    /**
     * Which platform hosts {@code company}, probing each in turn.
     *
     * <p>An entry may pin a platform explicitly as {@code "platform:handle"} — useful when a company
     * has boards on two platforms mid-migration, where the probe order would otherwise decide silently,
     * and required for any platform whose handle cannot be guessed from a bare name.
     */
    // Package-private for tests.
    Optional<Resolved> resolve(String company) {
        int colon = company.indexOf(':');
        if (colon > 0) {
            String prefix = company.substring(0, colon).trim();
            String handle = company.substring(colon + 1).trim();
            Optional<BoardPlatform> pinned = platform(prefix);
            if (pinned.isPresent() && !handle.isBlank()) {
                return pinned.map(p -> new Resolved(p, handle));
            }
        }
        for (BoardPlatform platform : platforms) {
            if (platform.hasBoard(company)) {
                return Optional.of(new Resolved(platform, company));
            }
        }
        return Optional.empty();
    }

    private Optional<BoardPlatform> platform(String id) {
        for (BoardPlatform platform : platforms) {
            if (platform.id().equalsIgnoreCase(id)) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    private String platformIds() {
        List<String> ids = new ArrayList<>();
        platforms.forEach(p -> ids.add(p.id()));
        return String.join(", ", ids);
    }

    // ---- inputs ------------------------------------------------------------------------------

    /**
     * Keep only postings whose location matches one of {@code terms}, case-insensitively.
     *
     * <p><strong>A posting with no location survives.</strong> Boards leave the field blank often
     * enough that dropping those would lose real roles on the strength of a missing value, and nothing
     * distinguishes an irrelevant location from an unstated one. Same rule as
     * {@code IngestionJob.connectionUnusable}: any doubt runs it.
     *
     * <p>Note the country name alone is usually not enough — most boards file a role as
     * {@code "Bengaluru"} with no country, so a term list should name cities.
     */
    // Package-private for tests.
    static List<RawItem> retainMatching(List<RawItem> items, List<String> terms) {
        if (terms.isEmpty() || items == null) {
            return items;
        }
        List<RawItem> kept = new ArrayList<>(items.size());
        for (RawItem item : items) {
            if (item.deleted() || matches(item, terms)) {
                kept.add(item);
            }
        }
        return List.copyOf(kept);
    }

    private static boolean matches(RawItem item, List<String> terms) {
        Object value = item.metadata() == null ? null : item.metadata().get("location");
        return AtsNormalization.matchesLocation(value == null ? null : value.toString(), terms);
    }

    /** The configured companies, de-duplicated and order-preserving. Accepts a list or a single string. */
    protected static List<String> companies(Knowledge knowledge) {
        return stringList(knowledge == null ? null : knowledge.inputs(), COMPANIES_INPUT, false);
    }

    /** The configured location terms, lowercased for matching. Empty means "keep everything". */
    protected static List<String> locations(Knowledge knowledge) {
        return locations(knowledge == null ? null : knowledge.inputs());
    }

    private static List<String> locations(Map<String, Object> inputs) {
        return stringList(inputs, LOCATIONS_INPUT, true);
    }

    private static List<String> stringList(Map<String, Object> inputs, String key, boolean lowercase) {
        Object raw = inputs == null ? null : inputs.get(key);
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof String s) {
            addIfPresent(out, s, lowercase);
        } else if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                addIfPresent(out, value == null ? null : value.toString(), lowercase);
            }
        }
        return List.copyOf(out);
    }

    private static void addIfPresent(Set<String> out, String value, boolean lowercase) {
        if (value != null && !value.isBlank()) {
            String trimmed = value.trim();
            out.add(lowercase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
        }
    }
}
