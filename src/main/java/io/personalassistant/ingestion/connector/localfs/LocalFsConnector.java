package io.personalassistant.ingestion.connector.localfs;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Local filesystem connector. The {@code rootPath} input is partitioned into iterables — one
 * per immediate sub-directory (walked recursively) plus a {@code root} iterable for the files
 * directly under the root — so large trees page independently with no overlap.
 *
 * <p>Files are ordered deterministically by {@code (lastModified, path)}. Forward grabs walk
 * items {@code >= anchor} ascending; backward grabs walk items {@code < anchor} descending. The
 * cursor {@code position} encodes the last emitted key so paging resumes exactly. File bytes are
 * never loaded into the entity — each item carries a {@code fileRef} the indexing stage reads.
 */
@ApplicationScoped
public class LocalFsConnector implements SourceConnector {

    static final String ROOT_ITERABLE = "root";
    private static final String PATH_KEY = "path";
    private static final String RECURSIVE_KEY = "recursive";
    private static final String SEP = "\t"; // position delimiter; tabs do not occur in paths here

    @Override
    public SourceType type() {
        return SourceType.LOCAL_FS;
    }

    @Override
    public void verify(Knowledge knowledge) {
        Path root = rootPath(knowledge);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("rootPath is not a readable directory: " + root);
        }
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        Path root = rootPath(knowledge);
        List<SourceIterable> iterables = new ArrayList<>();
        iterables.add(new SourceIterable(ROOT_ITERABLE, root.getFileName() + " (top-level files)",
                Map.of(PATH_KEY, root.toString(), RECURSIVE_KEY, false)));
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).sorted().forEach(dir ->
                    iterables.add(new SourceIterable(
                            root.relativize(dir).toString(),
                            dir.getFileName().toString(),
                            Map.of(PATH_KEY, dir.toString(), RECURSIVE_KEY, true))));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list directory " + root, e);
        }
        return iterables;
    }

    @Override
    public GrabPage grab(Knowledge knowledge, SourceIterable iterable, CursorDirection direction,
                         String position, int maxItems) {
        int cap = maxItems > 0 ? maxItems : 100;
        Path dir = Path.of((String) iterable.attributes().get(PATH_KEY));
        boolean recursive = Boolean.TRUE.equals(iterable.attributes().get(RECURSIVE_KEY));
        Instant anchor = knowledge.anchor();

        List<FileKey> matches = collectFiles(dir, recursive).stream()
                .filter(k -> withinDirection(k, anchor, direction))
                .filter(k -> afterPosition(k, position, direction))
                .sorted(orderFor(direction))
                .toList();

        if (matches.isEmpty()) {
            return GrabPage.end(position);
        }
        List<FileKey> page = matches.subList(0, Math.min(cap, matches.size()));
        List<RawItem> items = new ArrayList<>(page.size());
        for (FileKey k : page) {
            items.add(toRawItem(k));
        }
        FileKey last = page.get(page.size() - 1);
        boolean hasMore = matches.size() > page.size();
        return new GrabPage(items, encode(last), hasMore);
    }

    // ---- file collection & ordering ----------------------------------------------------------

    private List<FileKey> collectFiles(Path dir, boolean recursive) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FileKey> keys = new ArrayList<>();
        try (Stream<Path> walk = recursive ? Files.walk(dir) : Files.list(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    long millis = Files.getLastModifiedTime(p).toMillis();
                    keys.add(new FileKey(millis, p.toAbsolutePath().normalize().toString()));
                } catch (IOException ignored) {
                    // skip unreadable file
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk directory " + dir, e);
        }
        return keys;
    }

    private static boolean withinDirection(FileKey k, Instant anchor, CursorDirection direction) {
        Instant modified = Instant.ofEpochMilli(k.millis());
        // Boundary rule: forward handles >= anchor, backward handles < anchor.
        return direction == CursorDirection.FORWARD
                ? !modified.isBefore(anchor)
                : modified.isBefore(anchor);
    }

    private static boolean afterPosition(FileKey k, String position, CursorDirection direction) {
        if (position == null || position.isBlank()) {
            return true;
        }
        FileKey from = decode(position);
        int cmp = ASCENDING.compare(k, from);
        return direction == CursorDirection.FORWARD ? cmp > 0 : cmp < 0;
    }

    private static Comparator<FileKey> orderFor(CursorDirection direction) {
        return direction == CursorDirection.FORWARD ? ASCENDING : ASCENDING.reversed();
    }

    private static final Comparator<FileKey> ASCENDING =
            Comparator.comparingLong(FileKey::millis).thenComparing(FileKey::path);

    // ---- mapping & helpers -------------------------------------------------------------------

    private RawItem toRawItem(FileKey k) {
        Path path = Path.of(k.path());
        String name = path.getFileName().toString();
        String uri = path.toUri().toString();
        long size = sizeOf(path);
        String contentType = probeContentType(path);
        Instant modifiedAt = Instant.ofEpochMilli(k.millis());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("path", k.path());
        raw.put("sizeBytes", size);
        raw.put("lastModified", modifiedAt);
        raw.put("contentType", contentType);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", name);
        metadata.put("uri", uri);
        metadata.put("sizeBytes", size);
        metadata.put("modifiedAt", modifiedAt);

        return RawItem.file(k.path(), contentType, name, uri, "sha256:" + sha256(path),
                modifiedAt, k.path(), raw, metadata);
    }

    private Path rootPath(Knowledge knowledge) {
        Object root = knowledge.inputs() == null ? null : knowledge.inputs().get("rootPath");
        if (root == null) {
            throw new IllegalArgumentException("LOCAL_FS knowledge requires inputs.rootPath");
        }
        return Path.of(root.toString()).toAbsolutePath().normalize();
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String probeContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type != null ? type : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String encode(FileKey k) {
        return k.millis() + SEP + k.path();
    }

    private static FileKey decode(String position) {
        int i = position.indexOf(SEP);
        if (i < 0) {
            return new FileKey(0, position);
        }
        return new FileKey(Long.parseLong(position.substring(0, i)), position.substring(i + 1));
    }

    /** Ordering key: last-modified millis + absolute path (for deterministic, stable paging). */
    record FileKey(long millis, String path) {
    }
}
