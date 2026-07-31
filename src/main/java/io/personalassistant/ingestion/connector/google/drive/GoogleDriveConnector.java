package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.TokenWindowGrabber;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Google Drive connector. Like {@code LocalFsConnector} it turns a folder tree into independently
 * paged iterables, but over an authenticated cloud API — proving the {@link TokenWindowGrabber} base
 * against remote, paginated, mixed-content storage.
 *
 * <h2>Iterables</h2>
 * Discovery walks the folder tree breadth-first from the configured roots ({@code inputs.folderIds},
 * or {@code root} = My Drive by default) and emits one non-recursive iterable per folder. Drive's API
 * is itself per-parent ({@code '<folderId>' in parents}), so one-folder-per-iterable maps cleanly onto
 * it and lets each folder page independently. New folders appearing later are picked up by the
 * periodic discovery pass ({@link #hasDynamicIterables()} is true).
 *
 * <h2>Pagination</h2>
 * Drive exposes {@code modifiedTime} ordering and a {@code modifiedTime} predicate — the token-paged,
 * time-windowed shape {@link TokenWindowGrabber} drives — so this connector extends it and implements a
 * single {@link #fetchWindow}: translate the {@link TimeWindow} to a {@code modifiedTime} clause, list
 * (oldest-first for a forward window so the high-water advances cleanly, newest-first for a backfill
 * window), and map. The base owns the forward floor and the backward sweep. The lower bound is
 * {@code >=} so a file sharing the boundary timestamp is never skipped; the ingestion runner's checksum
 * ({@code version}) change-detection drops the re-listed boundary files, giving progress without gaps.
 *
 * <h2>Content</h2>
 * Google-native docs are exported to text ({@code Document}→text/plain, {@code Spreadsheet}→text/csv,
 * {@code Presentation}→text/plain) and carried inline in {@link RawItem#text()}. Binary files are
 * downloaded to a local scratch directory and referenced by {@link RawItem#fileRef()} so the existing
 * Tika indexing path parses them — exactly like a local file. Unsupported native types (forms, maps,
 * drawings) are skipped.
 */
@ApplicationScoped
public class GoogleDriveConnector extends TokenWindowGrabber {

    static final String ROOT_ALIAS = "root";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String NATIVE_PREFIX = "application/vnd.google-apps";
    private static final String FOLDER_KEY = "folderId";

    /** Google-native mime → export mime. Types absent here (forms, maps, drawings) are skipped. */
    private static final Map<String, String> EXPORT_AS = Map.of(
            "application/vnd.google-apps.document", "text/plain",
            "application/vnd.google-apps.spreadsheet", "text/csv",
            "application/vnd.google-apps.presentation", "text/plain");

    @ConfigProperty(name = "app.ingestion.google-drive.download-dir", defaultValue = "")
    String downloadDir;

    @ConfigProperty(name = "app.ingestion.google-drive.max-file-bytes", defaultValue = "26214400")
    long maxFileBytes;

    @ConfigProperty(name = "app.ingestion.google-drive.max-folders", defaultValue = "500")
    int maxFolders;

    private final DriveApi api;
    private final GoogleAccessTokens tokens;
    private final ConnectionResolver connections;

    @Inject
    public GoogleDriveConnector(DriveApi api, GoogleAccessTokens tokens, ConnectionResolver connections) {
        this.api = api;
        this.tokens = tokens;
        this.connections = connections;
    }

    @Override
    public SourceType type() {
        return SourceType.GOOGLE_DRIVE;
    }

    @Override
    public boolean requiresConnection() {
        return true; // Drive is OAuth-authenticated through a Connection
    }

    @Override
    public boolean hasDynamicIterables() {
        return true; // new folders can appear under the roots after activation
    }

    @Override
    public SyncSchedule defaultSchedule() {
        // No change-feed wired up, so incremental sync is a poll; 15 minutes balances freshness and
        // API load. Users can override per-knowledge.
        return SyncSchedule.ofInterval(Duration.ofMinutes(15));
    }

    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        // Drive's only input, folderIds, is a discovery-set dimension: it selects the roots of the
        // walk — i.e. WHICH folder iterables exist. Adding/removing a root is an iterable add/remove
        // handled by iterable-level reconcile (design §3.1: create/park), not a within-iterable
        // re-walk (§3.2). A folder iterable's membership is "the files directly in that folder id,"
        // independent of the configured root list, and fetchWindow applies no input-based file filter
        // (no fileTypes/query/globs) — so no input here moves the boundary *inside* an existing
        // iterable. Constant signature → editing folderIds never forces a needless re-walk of the
        // surviving folders (reconcile still walks any newly-added root's fresh cursor).
        return "";
    }

    @Override
    public void verifyConnection(Connection connection) {
        String token = tokens.bearer(connection);
        JsonNode about = api.about(token);
        if (!about.path("user").hasNonNull("emailAddress")) {
            throw new IllegalArgumentException(
                    "Google Drive credentials did not resolve to a user for connection " + connection.id());
        }
    }

    @Override
    public void verify(Knowledge knowledge) {
        // folderIds is optional (defaults to My Drive root); credentials are verified at the
        // connection level via verifyConnection. Nothing knowledge-specific to validate here.
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        String token = tokens.bearer(connections.resolve(knowledge));
        List<String> roots = configuredFolderIds(knowledge);

        List<SourceIterable> iterables = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Folder> queue = new ArrayDeque<>();
        for (String root : roots) {
            queue.add(new Folder(root, root.equals(ROOT_ALIAS) ? "My Drive" : root));
        }

        while (!queue.isEmpty() && iterables.size() < maxFolders) {
            Folder folder = queue.poll();
            if (!visited.add(folder.id)) {
                continue; // guard against shortcut cycles / diamonds
            }
            iterables.add(new SourceIterable(folder.id, folder.name, Map.of(FOLDER_KEY, folder.id)));
            for (Folder child : listSubfolders(token, folder.id)) {
                if (!visited.contains(child.id)) {
                    queue.add(child);
                }
            }
        }
        return iterables;
    }

    private List<Folder> listSubfolders(String token, String parentId) {
        String query = "'" + parentId + "' in parents and trashed=false and mimeType='" + FOLDER_MIME + "'";
        List<Folder> folders = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = api.listFiles(token, query, "name", pageToken, 200);
            for (JsonNode f : page.path("files")) {
                folders.add(new Folder(f.path("id").asText(), f.path("name").asText(f.path("id").asText())));
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null);
        return folders;
    }

    @Override
    protected Page fetchWindow(GrabContext ctx, TimeWindow window, String pageToken, int cap) {
        Object folderId = ctx.attributes().get(FOLDER_KEY);
        if (folderId == null) {
            return Page.end();
        }
        String token = tokens.bearer(connections.resolve(ctx.knowledge()));
        String query = childrenQuery(folderId.toString()) + windowClause(window);
        // A forward (lower-bounded) window lists oldest-first so the high-water advances cleanly; a
        // backfill window lists newest-first. Either way the base drains the whole window, so the order
        // is a free, source-native choice.
        String orderBy = window.hasLo() ? "modifiedTime,name" : "modifiedTime desc";
        JsonNode page = api.listFiles(token, query, orderBy, pageToken, cap);

        List<RawItem> items = new ArrayList<>();
        for (JsonNode f : page.path("files")) {
            RawItem item = toRawItem(token, f);
            if (item != null) {
                items.add(item);
            }
        }
        return new Page(items, page.path("nextPageToken").asText(null));
    }

    /** Translate the window's bounds into Drive's {@code modifiedTime} predicates (open bound = none). */
    private static String windowClause(TimeWindow window) {
        StringBuilder q = new StringBuilder();
        if (window.hasLo()) {
            q.append(" and modifiedTime >= '").append(window.lo()).append('\'');
        }
        if (window.hasHi()) {
            q.append(" and modifiedTime < '").append(window.hi()).append('\'');
        }
        return q.toString();
    }

    private static String childrenQuery(String folderId) {
        return "'" + folderId + "' in parents and trashed=false and mimeType!='" + FOLDER_MIME + "'";
    }

    // ---- file -> RawItem ----------------------------------------------------------------------

    private RawItem toRawItem(String token, JsonNode f) {
        String id = f.path("id").asText();
        String name = f.path("name").asText(id);
        String mimeType = f.path("mimeType").asText("application/octet-stream");
        Instant modifiedAt = parseTime(f.path("modifiedTime").asText(null));
        String uri = f.path("webViewLink").asText("https://drive.google.com/file/d/" + id + "/view");
        String checksum = "drive:" + id + ";v:" + f.path("version").asText("");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("name", name);
        raw.put("mimeType", mimeType);
        raw.put("modifiedTime", f.path("modifiedTime").asText(null));
        raw.put("version", f.path("version").asText(null));
        raw.put("md5Checksum", f.path("md5Checksum").asText(null));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", name);
        metadata.put("uri", uri);
        metadata.put("mimeType", mimeType);
        metadata.put("modifiedAt", modifiedAt);

        if (mimeType.startsWith(NATIVE_PREFIX)) {
            return nativeDoc(token, id, name, mimeType, uri, checksum, modifiedAt, raw, metadata);
        }
        return binaryFile(token, f, id, name, mimeType, uri, checksum, modifiedAt, raw, metadata);
    }

    /** Google-native doc: export to text and carry inline. Unsupported native types are skipped. */
    private RawItem nativeDoc(String token, String id, String name, String mimeType, String uri,
                              String checksum, Instant modifiedAt, Map<String, Object> raw,
                              Map<String, Object> metadata) {
        String exportMime = EXPORT_AS.get(mimeType);
        if (exportMime == null) {
            return null; // form / map / drawing — nothing textual to index
        }
        byte[] bytes = api.export(token, id, exportMime);
        String text = new String(bytes, StandardCharsets.UTF_8);
        return new RawItem(id, EntityType.PAGE, exportMime, name, uri, checksum, modifiedAt,
                raw, text, null, metadata, false);
    }

    /** Regular file: download bytes to local scratch and reference by fileRef (Tika reads it). */
    private RawItem binaryFile(String token, JsonNode f, String id, String name, String mimeType,
                               String uri, String checksum, Instant modifiedAt,
                               Map<String, Object> raw, Map<String, Object> metadata) {
        long size = f.path("size").asLong(-1);
        metadata.put("sizeBytes", size);
        raw.put("sizeBytes", size);
        if (size > maxFileBytes) {
            return null; // too large to download/index; skip
        }
        byte[] bytes = api.download(token, id);
        Path path = writeScratch(id, name, bytes);
        return RawItem.file(id, mimeType, name, uri, checksum, modifiedAt, path.toString(), raw, metadata);
    }

    private Path writeScratch(String id, String name, byte[] bytes) {
        try {
            Path dir = scratchDir();
            Files.createDirectories(dir);
            Path path = dir.resolve(id + "-" + sanitize(name));
            Files.write(path, bytes);
            return path;
        } catch (IOException e) {
            throw new GoogleApiException("Failed to stage Drive file " + id + " to scratch dir", e);
        }
    }

    private Path scratchDir() {
        String dir = downloadDir == null || downloadDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/psa-drive"
                : downloadDir;
        return Path.of(dir);
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(cleaned.length() - 120);
    }

    private static Instant parseTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(rfc3339);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> configuredFolderIds(Knowledge knowledge) {
        Object v = knowledge.inputs() == null ? null : knowledge.inputs().get("folderIds");
        if (v instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return List.of(ROOT_ALIAS);
    }

    private record Folder(String id, String name) {
    }
}
