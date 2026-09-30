package io.personalassistant.ingestion.connector.localfs;

import io.personalassistant.common.ContentTypes;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One iterable per immediate sub-directory (walked recursively), plus a root iterable for the files directly
 * under the root. A filesystem has no index, so the direction decides the order. Forward ({@code mtime >=
 * anchor}) needs mtime order, which is not walk order, so each page walks the subtree once into a bounded
 * max-heap: O(n log cap) time, O(cap) memory. Backward ({@code mtime < anchor}) is a one-time sweep in path
 * order, so its cursor is a path: the walk skips what it consumed and stops once the page is full.
 */
@ApplicationScoped
public class LocalFsConnector implements SourceConnector {

    static final String ROOT_ITERABLE = "root";
    private static final String PATH_KEY = "path";
    private static final String RECURSIVE_KEY = "recursive";
    private static final int DEFAULT_CAP = 100;
    private static final String POS_MILLIS = "lastModifiedMillis";
    private static final String POS_PATH = "path";

    /** Oldest first, path as a stable tie-break. */
    private static final Comparator<FileKey> ASCENDING =
            Comparator.comparingLong(FileKey::millis).thenComparing(FileKey::path);

    @Override
    public SourceType type() {
        return SourceType.LOCAL_FS;
    }

    @Override
    public boolean hasDynamicIterables() {
        return true;
    }

    @Override
    public SyncSchedule defaultSchedule() {
        // No change-feed: incremental sync re-walks the tree, so the default is a gentle daily re-arm.
        return SyncSchedule.ofInterval(Duration.ofDays(1));
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
    public GrabResult grab(GrabContext ctx) {
        Map<String, Object> attributes = ctx.attributes();
        CursorPosition position = ctx.cursor();
        int cap = ctx.maxItems() > 0 ? ctx.maxItems() : DEFAULT_CAP;

        Path dir = Path.of((String) attributes.get(PATH_KEY)).toAbsolutePath().normalize();
        boolean recursive = Boolean.TRUE.equals(attributes.get(RECURSIVE_KEY));

        if (!Files.isDirectory(dir)) {
            return GrabResult.end(position);
        }
        // The window's shape is the walk's sense; its bound is the anchor.
        TimeWindow window = ctx.seedWindow();
        return window.hasLo()
                ? grabForward(dir, recursive, window.lo(), position, cap)
                : grabBackward(dir, recursive, window.hi(), position, cap);
    }

    /**
     * One pass feeds a bounded max-heap whose top, the largest of the cap smallest, is evicted when a smaller
     * key arrives.
     */
    private GrabResult grabForward(Path dir, boolean recursive, Instant anchor,
                                   CursorPosition position, int cap) {
        FileKey from = position == null || position.isStart() ? null : decode(position);
        PriorityQueue<FileKey> heap = new PriorityQueue<>(ASCENDING.reversed());
        int[] qualifying = {0};

        forEachFile(dir, recursive, key -> {
            if (Instant.ofEpochMilli(key.millis()).isBefore(anchor)) {
                return; // forward window is mtime >= anchor
            }
            if (from != null && ASCENDING.compare(key, from) <= 0) {
                return; // resume strictly after the cursor
            }
            qualifying[0]++;
            if (heap.size() < cap) {
                heap.offer(key);
            } else if (ASCENDING.compare(key, heap.peek()) < 0) {
                heap.poll();
                heap.offer(key);
            }
        });

        if (heap.isEmpty()) {
            return GrabResult.end(position);
        }
        List<FileKey> page = new ArrayList<>(heap);
        page.sort(ASCENDING);
        boolean hasMore = qualifying[0] > page.size();
        return new GrabResult(toItems(page), encode(page.get(page.size() - 1)), hasMore);
    }

    private void forEachFile(Path dir, boolean recursive, Consumer<FileKey> sink) {
        try (Stream<Path> walk = recursive ? Files.walk(dir) : Files.list(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    long millis = Files.getLastModifiedTime(p).toMillis();
                    sink.accept(new FileKey(millis, p.toAbsolutePath().normalize().toString()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk directory " + dir, e);
        }
    }

    /**
     * The DFS skips everything the cursor path already consumed and stops at cap + 1 files, so a page touches
     * only the cursor's path plus the next cap files.
     */
    private GrabResult grabBackward(Path dir, boolean recursive, Instant anchor,
                                    CursorPosition position, int cap) {
        String cursorPath = position == null || position.isStart() ? null : decode(position).path();
        String[] spine = spineComponents(dir, cursorPath);

        BackwardWalk walk = new BackwardWalk(anchor, spine, cap + 1, recursive);
        walk.descend(dir, 0, spine != null);

        List<FileKey> found = walk.found;
        if (found.isEmpty()) {
            return GrabResult.end(position);
        }
        boolean hasMore = found.size() > cap;
        List<FileKey> page = hasMore ? found.subList(0, cap) : found;
        return new GrabResult(toItems(page), encode(page.get(page.size() - 1)), hasMore);
    }

    private static String[] spineComponents(Path dir, String cursorPath) {
        if (cursorPath == null) {
            return null;
        }
        Path cursor = Path.of(cursorPath).toAbsolutePath().normalize();
        if (!cursor.startsWith(dir)) {
            return null; // cursor not under this iterable — start from the top
        }
        Path rel = dir.relativize(cursor);
        int n = rel.getNameCount();
        String[] components = new String[n];
        for (int i = 0; i < n; i++) {
            components[i] = rel.getName(i).toString();
        }
        return components;
    }

    /**
     * Emits files in component-wise path order, resuming after a cursor. {@code found} holds at most limit
     * (cap + 1) keys, so the caller can detect hasMore.
     */
    private static final class BackwardWalk {
        private final Instant anchor;
        private final String[] spine;   // cursor components, or null on the first page
        private final int limit;
        private final boolean recursive;
        final List<FileKey> found = new ArrayList<>();

        BackwardWalk(Instant anchor, String[] spine, int limit, boolean recursive) {
            this.anchor = anchor;
            this.spine = spine;
            this.limit = limit;
            this.recursive = recursive;
        }

        boolean descend(Path dir, int depth, boolean onSpine) {
            List<Path> children;
            try (Stream<Path> s = Files.list(dir)) {
                children = s.sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to list directory " + dir, e);
            }
            for (Path child : children) {
                String name = child.getFileName().toString();
                boolean isDir = Files.isDirectory(child);
                if (onSpine && spine != null && depth < spine.length) {
                    int cmp = name.compareTo(spine[depth]);
                    boolean isLast = depth == spine.length - 1;
                    if (cmp < 0) {
                        continue; // sorted before the cursor — already consumed
                    } else if (cmp == 0) {
                        // On the cursor's spine: recurse into the matching directory; a matching file is the
                        // cursor itself and is skipped.
                        if (isDir && recursive && descend(child, depth + 1, !isLast)) {
                            return true;
                        }
                        continue;
                    }
                    // cmp > 0 falls through to the "fresh" handling below
                }
                if (isDir) {
                    if (recursive && descend(child, depth + 1, false)) {
                        return true;
                    }
                } else if (accept(child)) {
                    return true;
                }
            }
            return false;
        }

        private boolean accept(Path file) {
            long millis;
            try {
                millis = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                return false;
            }
            if (!Instant.ofEpochMilli(millis).isBefore(anchor)) {
                return false; // backward window is mtime < anchor
            }
            found.add(new FileKey(millis, file.toAbsolutePath().normalize().toString()));
            return found.size() >= limit;
        }
    }

    private static CursorPosition encode(FileKey k) {
        return CursorPosition.builder()
                .put(POS_MILLIS, k.millis())
                .put(POS_PATH, k.path())
                .build();
    }

    private static FileKey decode(CursorPosition position) {
        return new FileKey(position.getLong(POS_MILLIS, 0L), position.getString(POS_PATH));
    }

    /**
     * Only reached when fetching is forced globally, but still the one path that notices a file deleted under
     * us, since no walk emits tombstones.
     */
    @Override
    public Optional<RawItem> fetchOne(Knowledge knowledge, Entity entity) {
        Path path = Path.of(entity.externalId());
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(toRawItem(
                    new FileKey(Files.getLastModifiedTime(path).toMillis(), path.toString())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stat " + path, e);
        }
    }

    private List<RawItem> toItems(List<FileKey> keys) {
        List<RawItem> items = new ArrayList<>(keys.size());
        for (FileKey k : keys) {
            items.add(toRawItem(k));
        }
        return items;
    }

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

        return RawItem.file(k.path(), contentType, name, uri, checksum(size, k.millis()),
                modifiedAt, k.path(), raw, metadata);
    }

    /**
     * Size and mtime from one stat, no byte reads: any normal edit moves the mtime, as rsync's quick check
     * assumes.
     */
    private static String checksum(long sizeBytes, long lastModifiedMillis) {
        return "size:" + sizeBytes + ";mtime:" + lastModifiedMillis;
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

    /**
     * ContentTypes sniffs the name and the bytes; {@code Files.probeContentType} alone returns null for .xlsx
     * on macOS.
     */
    private static String probeContentType(Path path) {
        return ContentTypes.detect(path);
    }

    record FileKey(long millis, String path) {
    }
}
