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
 * One connector across every ATS: a company is an iterable, and which platform hosts it is resolved once in
 * discover and carried on the iterable's attributes. Each platform returns the whole board in one request, so
 * grab ignores the seed window and returns one page; change detection skips unchanged postings. Forward-only:
 * a closed posting just vanishes from the snapshot, which is why this connector opts into retention.
 */
@ApplicationScoped
public class JobBoardsConnector implements SourceConnector {

    private static final Logger LOG = Logger.getLogger(JobBoardsConnector.class.getName());

    public static final String COMPANIES_INPUT = "companies";

    /**
     * Display names keyed by the {@link #COMPANIES_INPUT} entry as written. A map beside the list because an
     * entry is an iterable id: renaming must not change the iterable or reset its cursor. Left out of
     * membershipSignature.
     */
    public static final String COMPANY_LABELS_INPUT = "companyLabels";

    /**
     * Empty keeps every posting. The highest-leverage setting: a global board carries mostly roles elsewhere,
     * and everything kept is embedded.
     */
    public static final String LOCATIONS_INPUT = "locations";

    /** Title is in every listing, so filtering on it also saves the per-posting detail call. */
    public static final String TITLE_INCLUDE_INPUT = "titleInclude";
    public static final String TITLE_EXCLUDE_INPUT = "titleExclude";

    /** Absent or 0 means no limit. */
    public static final String MAX_AGE_DAYS_INPUT = "maxAgeDays";

    /** True lets a stated-remote posting satisfy the location terms however it is filed. */
    public static final String INCLUDE_REMOTE_INPUT = "includeRemote";

    public static final String PLATFORM_ATTRIBUTE = "platform";
    public static final String HANDLE_ATTRIBUTE = "handle";

    /** Observability only: grabs are stateless. */
    private static final String POS_SNAPSHOT_AT = "snapshotAtMillis";

    /** Boards change on a human timescale. */
    private static final Duration POLL_INTERVAL = Duration.ofHours(3);

    /**
     * Longer than the poll interval, since a surviving posting is re-created by the next walk, and short
     * enough that a filled role does not linger.
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
        // The company list only changes on an edit, which re-runs discover.
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
     * Excludes companies, since each is its own iterable handled by discover-reconcile, and labels, which
     * change filing, not membership. The filter terms move the boundary inside each board, so changing one
     * must re-walk: change detection alone never revisits a board.
     */
    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        BoardFilter f = filter(inputs);
        return String.join(",", f.locations()) + "|" + String.join(",", f.titleInclude())
                + "|" + String.join(",", f.titleExclude())
                + "|" + (f.maxAge() == null ? "" : f.maxAge().toDays())
                + "|" + f.includeRemote();
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
            // Every one failed: a typo or an outage, so refuse rather than activate a dead knowledge.
            throw new IllegalArgumentException("No supported job board found for any of: "
                    + String.join(", ", unresolved) + ". Supported platforms: " + platformIds());
        }
        if (!unresolved.isEmpty()) {
            // A partial miss is normal.
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
                // A cursor from before resolution was stored, or a platform since removed: re-resolve.
                .or(() -> resolve(context.iterableId()))
                .orElseThrow(() -> new AtsApiException(
                        "No supported job board for '" + context.iterableId() + "'"));

        // The platform got the filter as a hint; this pass is authoritative, and it is what bounds the cost
        // of a knowledge.
        BoardFilter filter = filter(context.knowledge());
        List<RawItem> items = retainMatching(
                resolved.platform().fetch(resolved.handle(),
                        label(context.knowledge(), context.iterableId()), filter),
                filter);

        CursorPosition position = context.cursor().toBuilder()
                .put(POS_SNAPSHOT_AT, Instant.now().toEpochMilli())
                .build();
        // One snapshot is the whole board: hasMore=false parks a forward cursor IDLE.
        return new GrabResult(items, position, false);
    }

    /**
     * Resolves candidate names without creating anything. Location matches are not counted: that would need
     * the full board, one request per posting on some platforms.
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

    /** @param platform null when no supported platform has a board for it: an answer, not an error */
    public record CompanyLookup(String company, String platform, String handle, int postings) {}

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
     * An entry may pin a platform as {@code platform:handle}, for a company on two platforms mid-migration or
     * a handle that cannot be guessed from a name.
     */
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

    /**
     * A posting with no location or no posted date survives: boards leave them blank often. Name cities, not
     * the country: boards usually file a role under the city alone.
     */
    static List<RawItem> retainMatching(List<RawItem> items, BoardFilter filter) {
        if (items == null || filter.isEmpty()) {
            return items;
        }
        List<RawItem> kept = new ArrayList<>(items.size());
        for (RawItem item : items) {
            if (filter.matches(item)) {
                kept.add(item);
            }
        }
        return List.copyOf(kept);
    }

    /** De-duplicated and order-preserving; accepts a list or a single string. */
    protected static List<String> companies(Knowledge knowledge) {
        return stringList(knowledge == null ? null : knowledge.inputs(), COMPANIES_INPUT, false);
    }

    static String label(Knowledge knowledge, String company) {
        Object labels = knowledge == null || knowledge.inputs() == null
                ? null : knowledge.inputs().get(COMPANY_LABELS_INPUT);
        if (!(labels instanceof Map<?, ?> map) || company == null) {
            return null;
        }
        Object label = map.get(company);
        return label instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    protected static List<String> locations(Knowledge knowledge) {
        return locations(knowledge == null ? null : knowledge.inputs());
    }

    private static List<String> locations(Map<String, Object> inputs) {
        return stringList(inputs, LOCATIONS_INPUT, true);
    }

    static BoardFilter filter(Knowledge knowledge) {
        return filter(knowledge == null ? null : knowledge.inputs());
    }

    private static BoardFilter filter(Map<String, Object> inputs) {
        return new BoardFilter(
                stringList(inputs, LOCATIONS_INPUT, true),
                stringList(inputs, TITLE_INCLUDE_INPUT, true),
                stringList(inputs, TITLE_EXCLUDE_INPUT, true),
                days(inputs, MAX_AGE_DAYS_INPUT),
                bool(inputs, INCLUDE_REMOTE_INPUT));
    }

    /** A positive whole number, however the console encoded it. */
    private static Duration days(Map<String, Object> inputs, String key) {
        Object raw = inputs == null ? null : inputs.get(key);
        long value;
        if (raw instanceof Number n) {
            value = n.longValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null; // an unparseable limit is no limit, not an empty knowledge
            }
        } else {
            return null;
        }
        return value > 0 ? Duration.ofDays(value) : null;
    }

    private static boolean bool(Map<String, Object> inputs, String key) {
        Object raw = inputs == null ? null : inputs.get(key);
        return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
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
