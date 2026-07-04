package io.personalassistant.ingestion.connector.google.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory {@link DriveApi} for connector tests. It models the slices of Drive the connector uses:
 * {@code '<parent>' in parents} filtering, folder vs non-folder selection, {@code modifiedTime}
 * windows and ordering (asc/desc), offset page tokens, plus media download and native export. This
 * exercises the connector's folder-tree discovery and direction-specific pagination without network.
 */
class FakeDriveApi implements DriveApi {

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final Pattern PARENT = Pattern.compile("'([^']+)' in parents");
    private static final Pattern GTE = Pattern.compile("modifiedTime >= '([^']+)'");
    private static final Pattern LT = Pattern.compile("modifiedTime < '([^']+)'");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Item> items = new ArrayList<>();

    FakeDriveApi folder(String id, String name, String parentId) {
        items.add(new Item(id, name, FOLDER_MIME, parentId, 0L, 1, 0, null, null));
        return this;
    }

    FakeDriveApi nativeDoc(String id, String name, String mimeType, String parentId, long modifiedMs,
                           int version, String exportText) {
        items.add(new Item(id, name, mimeType, parentId, modifiedMs, version, 0, null, exportText));
        return this;
    }

    FakeDriveApi binary(String id, String name, String mimeType, String parentId, long modifiedMs,
                        int version, byte[] bytes) {
        items.add(new Item(id, name, mimeType, parentId, modifiedMs, version, bytes.length, bytes, null));
        return this;
    }

    @Override
    public JsonNode listFiles(String accessToken, String query, String orderBy, String pageToken, int pageSize) {
        String parent = group(PARENT, query);
        boolean foldersOnly = query.contains("mimeType='" + FOLDER_MIME + "'");
        boolean nonFolders = query.contains("mimeType!='" + FOLDER_MIME + "'");
        Instant gte = time(GTE, query);
        Instant lt = time(LT, query);

        List<Item> matched = new ArrayList<>();
        for (Item it : items) {
            if (parent != null && !parent.equals(it.parentId)) {
                continue;
            }
            boolean isFolder = FOLDER_MIME.equals(it.mimeType);
            if (foldersOnly && !isFolder) {
                continue;
            }
            if (nonFolders && isFolder) {
                continue;
            }
            Instant modified = Instant.ofEpochMilli(it.modifiedMs);
            if (gte != null && modified.isBefore(gte)) {
                continue;
            }
            if (lt != null && !modified.isBefore(lt)) {
                continue;
            }
            matched.add(it);
        }

        boolean desc = orderBy != null && orderBy.contains("desc");
        Comparator<Item> cmp = Comparator.comparingLong((Item i) -> i.modifiedMs).thenComparing(i -> i.name);
        matched.sort(desc ? cmp.reversed() : cmp);

        int offset = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
        int end = Math.min(matched.size(), offset + pageSize);

        ObjectNode result = mapper.createObjectNode();
        ArrayNode files = result.putArray("files");
        for (int i = offset; i < end; i++) {
            files.add(toNode(matched.get(i)));
        }
        if (end < matched.size()) {
            result.put("nextPageToken", String.valueOf(end));
        }
        return result;
    }

    @Override
    public byte[] download(String accessToken, String fileId) {
        return find(fileId).bytes;
    }

    @Override
    public byte[] export(String accessToken, String fileId, String exportMimeType) {
        return find(fileId).exportText.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public JsonNode about(String accessToken) {
        ObjectNode about = mapper.createObjectNode();
        about.putObject("user").put("emailAddress", "user@example.com");
        return about;
    }

    private ObjectNode toNode(Item it) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", it.id);
        n.put("name", it.name);
        n.put("mimeType", it.mimeType);
        n.put("modifiedTime", Instant.ofEpochMilli(it.modifiedMs).toString());
        n.put("version", String.valueOf(it.version));
        n.put("webViewLink", "https://drive.google.com/file/d/" + it.id + "/view");
        if (it.size > 0) {
            n.put("size", String.valueOf(it.size));
        }
        n.put("trashed", false);
        return n;
    }

    private Item find(String id) {
        return items.stream().filter(i -> i.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such file " + id));
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Instant time(Pattern p, String s) {
        String v = group(p, s);
        return v == null ? null : Instant.parse(v);
    }

    private record Item(String id, String name, String mimeType, String parentId, long modifiedMs,
                        int version, long size, byte[] bytes, String exportText) {
    }
}
