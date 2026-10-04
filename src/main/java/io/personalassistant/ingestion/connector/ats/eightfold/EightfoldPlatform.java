package io.personalassistant.ingestion.connector.ats.eightfold;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Eightfold careers sites. A site is a host/domain pair, never derivable from a name, and speaks one of two
 * APIs, so every poll asks PCSX first and falls back to {@code apply/v2}. Both page ten at a time and carry
 * no description, so each position kept costs a detail call: the filter runs on the listing first, and a
 * failed detail fetch skips that position rather than failing the board. On PCSX the location terms go out
 * as server-side queries, one per term and unioned, which is what keeps a 2,000-position board inside a
 * lease; a site with no locations walks the whole board.
 */
@ApplicationScoped
public class EightfoldPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(EightfoldPlatform.class.getName());

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** Well above the largest board seen. */
    private static final int MAX_PAGES = 500;

    private final EightfoldApi api;

    @Inject
    public EightfoldPlatform(EightfoldApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "eightfold";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        Optional<EightfoldSite> site = EightfoldSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int count = pcsxCount(site.get());
            if (count == 0) {
                count = api.listPositions(site.get(), 0).path("count").asInt(0);
            }
            return count > 0 ? OptionalInt.of(count) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        EightfoldSite site = EightfoldSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not an Eightfold site: '" + handle
                        + "'. Expected host/domain (e.g. explore.jobs.netflix.net/netflix.com) or the careers"
                        + " URL with its domain= parameter."));
        boolean pcsx = pcsxCount(site) > 0;
        List<RawItem> items = new ArrayList<>();
        for (Listed listed : pcsx ? searchPcsx(site, filter) : listV2(site)) {
            // Filtered before the detail call: a position passes when any of its locations does.
            if (!filter.matchesTitle(listed.title()) || !matchesAnyLocation(listed, filter)) {
                continue;
            }
            RawItem item = toItem(site, company, listed, pcsx);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 0 for a v2 tenant, which answers PCSX with a 403, and for an empty one. A 429 propagates: read as "not
     * PCSX" it would fall back to v2, which a PCSX tenant answers with no positions.
     */
    private int pcsxCount(EightfoldSite site) {
        try {
            return api.search(site, "", 0).path("data").path("count").asInt(0);
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Fields both listings carry, under each API's own names. */
    private record Listed(String id, String title, List<String> locations, String department,
                          String workOption, Long created, Long updated, String url) {}

    private List<Listed> searchPcsx(EightfoldSite site, BoardFilter filter) {
        List<String> queries = filter.locations().isEmpty()
                ? List.of("")
                : List.copyOf(new LinkedHashSet<>(filter.locations()));
        // Keyed by id: a position matched by two terms costs one detail call.
        Map<String, Listed> found = new LinkedHashMap<>();
        for (String query : queries) {
            int start = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                JsonNode data = api.search(site, query, start).path("data");
                JsonNode positions = data.path("positions");
                if (!positions.isArray() || positions.isEmpty()) {
                    break;
                }
                for (JsonNode p : positions) {
                    String id = text(p, "id");
                    if (id != null) {
                        found.putIfAbsent(id, new Listed(id, text(p, "name"), locations(p, "locations", "location"),
                                text(p, "department"), text(p, "workLocationOption"), seconds(p, "creationTs"),
                                seconds(p, "postedTs"), absolute(site, text(p, "positionUrl"))));
                    }
                }
                start += positions.size();
                if (start >= data.path("count").asInt(start)) {
                    break;
                }
            }
        }
        return List.copyOf(found.values());
    }

    private List<Listed> listV2(EightfoldSite site) {
        List<Listed> found = new ArrayList<>();
        int start = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.listPositions(site, start);
            JsonNode positions = response.path("positions");
            if (!positions.isArray() || positions.isEmpty()) {
                break;
            }
            for (JsonNode p : positions) {
                String id = text(p, "id");
                if (id != null) {
                    found.add(new Listed(id, text(p, "name"), locations(p, "locations", "location"),
                            text(p, "department"), text(p, "work_location_option"), seconds(p, "t_create"),
                            seconds(p, "t_update"), text(p, "canonicalPositionUrl")));
                }
            }
            start += positions.size();
            if (start >= response.path("count").asInt(start)) {
                break;
            }
        }
        return found;
    }

    private static boolean matchesAnyLocation(Listed listed, BoardFilter filter) {
        return listed.locations().isEmpty() || listed.locations().stream()
                .anyMatch(l -> AtsNormalization.matchesLocation(l, filter.locations()));
    }

    private RawItem toItem(EightfoldSite site, String label, Listed listed, boolean pcsx) {
        if (listed.title() == null) {
            return null;
        }
        String content;
        try {
            content = pcsx
                    ? api.positionDetails(site, listed.id()).path("data").path("jobDescription").asText("")
                    : api.position(site, listed.id()).path("job_description").asText("");
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Withdrawn since the listing, or transient: skipping one position beats failing the board.
            LOG.log(Level.FINE, "Could not fetch Eightfold position " + listed.id(), e);
            return null;
        }

        String id = listed.id();
        String title = listed.title();
        String uri = firstNonBlank(listed.url(), "https://" + site.host() + "/careers/job/" + id);
        String location = String.join("; ", listed.locations());
        String companyName = AtsNormalization.company(label, site.domain());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: the work option is onsite / hybrid / remote.
        metadata.put("remote", "remote".equalsIgnoreCase(listed.workOption())
                || AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", listed.department());
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", instant(listed.created()));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("site", site.toString());
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                AtsNormalization.withCompany(
                        "eightfold:" + id + ";upd:" + (listed.updated() == null ? "" : listed.updated())
                                + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, site.domain()),
                instant(listed.updated()),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    private static List<String> locations(JsonNode position, String listField, String singleField) {
        List<String> out = new ArrayList<>();
        for (JsonNode location : position.path(listField)) {
            String text = location.asText("").trim();
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
        }
        String single = text(position, singleField);
        if (out.isEmpty() && single != null) {
            out.add(single);
        }
        return out;
    }

    /** PCSX gives a site-relative path. */
    private static String absolute(EightfoldSite site, String path) {
        if (path == null || path.startsWith("http")) {
            return path;
        }
        return "https://" + site.host() + (path.startsWith("/") ? "" : "/") + path;
    }

    /** Epoch seconds, as Eightfold states its dates. */
    private static Long seconds(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.canConvertToLong() && value.asLong() > 0 ? value.asLong() : null;
    }

    private static Instant instant(Long epochSeconds) {
        return AtsNormalization.instantOrNull(epochSeconds == null ? null : epochSeconds * 1000);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
