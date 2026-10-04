package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ConfigText;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.TokenWindowGrabber;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleApiException;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
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
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One non-recursive iterable per folder, found breadth-first from the configured roots (My Drive by default).
 * Extends TokenWindowGrabber over modifiedTime; the lower bound is {@code >=}, so a file on the boundary is
 * never skipped and the version checksum drops the re-listed ones. Native docs are exported to inline text;
 * binary files are staged to a scratch dir as a fileRef. Neither transfer happens in the walk: fetchWindow
 * maps metadata only, and materialize fetches just the items the runner keeps.
 */
@ApplicationScoped
public class GoogleDriveConnector extends TokenWindowGrabber {

    private static final Logger LOG = Logger.getLogger(GoogleDriveConnector.class.getName());

    static final String ROOT_ALIAS = "root";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String NATIVE_PREFIX = "application/vnd.google-apps";
    private static final String FOLDER_KEY = "folderId";

    /** Types absent here (forms, maps, drawings) are skipped. */
    private static final Map<String, String> EXPORT_AS = Map.of(
            "application/vnd.google-apps.document", "text/plain",
            "application/vnd.google-apps.spreadsheet", "text/csv",
            "application/vnd.google-apps.presentation", "text/plain");

    /** Blank falls back to {@code ${java.io.tmpdir}/psa-drive}. */
    @ConfigProperty(name = "app.ingestion.google-drive.download-dir")
    Optional<String> downloadDir;

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
        return true;
    }

    @Override
    public boolean hasDynamicIterables() {
        return true;
    }

    @Override
    public SyncSchedule defaultSchedule() {
        // No change-feed, so incremental sync is a poll.
        return SyncSchedule.ofInterval(Duration.ofMinutes(15));
    }

    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        // folderIds only chooses which folder iterables exist, which reconcile handles, and no input filters
        // files inside a folder: nothing moves a boundary within one, so the signature is constant.
        return "";
    }

    @Override
    public void verifyConnection(Connection connection) {
        GoogleAuth token = tokens.authFor(connection);
        JsonNode about = api.about(token);
        if (!about.path("user").hasNonNull("emailAddress")) {
            throw new IllegalArgumentException(
                    "Google Drive credentials did not resolve to a user for connection " + connection.id());
        }
    }

    @Override
    public void verify(Knowledge knowledge) {
        // Nothing knowledge-specific to validate: credentials are verified per connection.
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        GoogleAuth token = tokens.authFor(connections.resolve(knowledge));
        List<String> roots = configuredFolderIds(knowledge);

        List<SourceIterable> iterables = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Folder> queue = new ArrayDeque<>();
        for (String root : roots) {
            queue.add(new Folder(root, rootName(token, root)));
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

    /**
     * A configured root is a bare id that no listing names. Falls back to the id rather than failing
     * discovery.
     */
    private String rootName(GoogleAuth token, String root) {
        if (ROOT_ALIAS.equals(root)) {
            return "My Drive";
        }
        try {
            String name = api.fileName(token, root);
            return name == null || name.isBlank() ? root : name;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the name of Drive folder " + root, e);
            return root;
        }
    }

    private List<Folder> listSubfolders(GoogleAuth token, String parentId) {
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
        GoogleAuth token = tokens.authFor(connections.resolve(ctx.knowledge()));
        String query = childrenQuery(folderId.toString()) + windowClause(window);
        // Forward lists oldest-first so the high-water mark advances cleanly; the base drains the whole
        // window either way.
        String orderBy = window.hasLo() ? "modifiedTime,name" : "modifiedTime desc";
        JsonNode page = api.listFiles(token, query, orderBy, pageToken, cap);

        List<RawItem> items = new ArrayList<>();
        for (JsonNode f : page.path("files")) {
            RawItem item = toRawItem(f);
            if (item != null) {
                items.add(item);
            }
        }
        return new Page(items, page.path("nextPageToken").asText(null));
    }

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

    private RawItem toRawItem(JsonNode f) {
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
            return nativeDoc(id, name, mimeType, uri, checksum, modifiedAt, raw, metadata);
        }
        return binaryFile(f, id, name, mimeType, uri, checksum, modifiedAt, raw, metadata);
    }

    /**
     * The export is deferred to materialize, with the export mime recorded as the content type so that call
     * needs no lookup. An unsupported type is rejected here, which costs nothing.
     */
    private RawItem nativeDoc(String id, String name, String mimeType, String uri,
                              String checksum, Instant modifiedAt, Map<String, Object> raw,
                              Map<String, Object> metadata) {
        String exportMime = EXPORT_AS.get(mimeType);
        if (exportMime == null) {
            return null; // form / map / drawing: nothing textual to index
        }
        return new RawItem(id, EntityType.PAGE, exportMime, name, uri, checksum, modifiedAt,
                raw, null, null, metadata, null, false);
    }

    /**
     * Recorded by the path its bytes will occupy, without downloading. The size cap applies here, from the
     * listing, so an oversized file is never fetched.
     */
    private RawItem binaryFile(JsonNode f, String id, String name, String mimeType,
                               String uri, String checksum, Instant modifiedAt,
                               Map<String, Object> raw, Map<String, Object> metadata) {
        long size = f.path("size").asLong(-1);
        metadata.put("sizeBytes", size);
        raw.put("sizeBytes", size);
        if (size > maxFileBytes) {
            return null; // too large to download or index
        }
        return RawItem.file(id, mimeType, name, uri, checksum, modifiedAt,
                stagedPath(id, name).toString(), raw, metadata);
    }

    /**
     * The only place this connector transfers content. A binary is staged at the very path binaryFile
     * reported, so what is written and what is stored cannot drift.
     */
    @Override
    public Entity.Content materialize(Knowledge knowledge, RawItem item) {
        GoogleAuth token = tokens.authFor(connections.resolve(knowledge));
        String mimeType = String.valueOf(item.raw().get("mimeType"));
        if (mimeType.startsWith(NATIVE_PREFIX)) {
            byte[] bytes = api.export(token, item.externalId(), item.contentType());
            return Entity.Content.ofText(new String(bytes, StandardCharsets.UTF_8));
        }
        byte[] bytes = api.download(token, item.externalId());
        return Entity.Content.ofFile(stage(item.externalId(), Path.of(item.fileRef()), bytes).toString());
    }

    /** Drive's fileRef is a staged copy in a temp dir the OS purges, so a re-index must fetch again. */
    @Override
    public ReindexMode defaultReindexMode() {
        return ReindexMode.FETCH_AND_REINDEX;
    }

    /**
     * Empty when the id stopped resolving, the file was trashed, or it now maps to nothing indexable. The
     * walk treats all of those as absent too, so the two paths agree.
     */
    @Override
    public Optional<RawItem> fetchOne(Knowledge knowledge, Entity entity) {
        GoogleAuth token = tokens.authFor(connections.resolve(knowledge));
        JsonNode file;
        try {
            file = api.getFile(token, entity.externalId());
        } catch (GoogleApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
        if (file == null || file.isMissingNode() || file.path("trashed").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toRawItem(file));
    }

    private Path stage(String id, Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
            return path;
        } catch (IOException e) {
            throw new GoogleApiException("Failed to stage Drive file " + id + " to scratch dir", e);
        }
    }

    /** Deterministic in (id, name), so the path can be named during the walk and a re-fetch overwrites it. */
    private Path stagedPath(String id, String name) {
        return scratchDir().resolve(id + "-" + sanitize(name));
    }

    private Path scratchDir() {
        return Path.of(ConfigText.orElse(downloadDir,
                System.getProperty("java.io.tmpdir") + "/psa-drive"));
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
