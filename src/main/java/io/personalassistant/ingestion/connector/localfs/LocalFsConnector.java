package io.personalassistant.ingestion.connector.localfs;

import io.personalassistant.domain.model.CursorPosition;
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
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Local filesystem connector. The {@code rootPath} input is partitioned into iterables — one per
 * immediate sub-directory (walked recursively) plus a {@code root} iterable for the files directly
 * under the root — so large trees page independently with no overlap.
 *
 * <p>Unlike the cloud connectors it does <em>not</em> extend a shared grabber base
 * ({@code TokenWindowGrabber}/{@code TimeWindowGrabber}): a filesystem has no continuation token and no
 * server-side time filter, so it implements {@link SourceConnector} directly and reads the walk's sense
 * (forward vs. backfill) from the seed {@link TimeWindow}'s shape.
 *
 * <h2>Why pagination is direction-specific</h2>
 * A filesystem exposes no index, so the only way to enumerate it is to walk it — and a walk visits
 * files in <em>path/tree</em> order, never in modified-time order. That mismatch drives the design:
 *
 * <ul>
 *   <li><b>Forward (incremental, {@code mtime >= anchor}).</b> Catching files changed since the
 *       anchor is inherently modified-time driven, so this direction is ordered by
 *       {@code (lastModified, path)} ascending. Because that key is <em>not</em> the walk order, the
 *       cursor cannot tell the walk where to resume — every page must visit the subtree. We keep
 *       that O(n) walk but make each page cheap: a single streaming pass feeds a bounded max-heap of
 *       size {@code maxItems}, so we select the next page in O(n·log&nbsp;cap) time and
 *       O(cap) memory — no full sort, no full materialisation.</li>
 *   <li><b>Backward (backfill, {@code mtime < anchor}).</b> History is a one-time complete sweep, so
 *       its order is free to choose. We order it by <em>path</em> (component-wise), which makes the
 *       cursor a path and lets the walk do real work: it skips every subtree already consumed and
 *       stops as soon as the page is full — O(cap) time and memory per page, no re-walk, no sort.
 *       The {@code mtime < anchor} predicate is still applied per file so files created after
 *       activation are left to the forward direction.</li>
 * </ul>
 *
 * <p>The pagination state is a two-field {@link CursorPosition} —
 * {@code {"lastModifiedMillis": <long>, "path": <string>}}. Forward reads both fields; backward
 * reads only {@code path}. File bytes are never loaded into the entity — each item carries a
 * {@code fileRef} the indexing stage reads.
 */
@ApplicationScoped
public class LocalFsConnector implements SourceConnector {

    static final String ROOT_ITERABLE = "root";
    private static final String PATH_KEY = "path";
    private static final String RECURSIVE_KEY = "recursive";
    private static final int DEFAULT_CAP = 100;
    // CursorPosition field names this connector owns:
    private static final String POS_MILLIS = "lastModifiedMillis";
    private static final String POS_PATH = "path";

    /** Total order used by the forward direction: oldest first, path as a stable tie-break. */
    private static final Comparator<FileKey> ASCENDING =
            Comparator.comparingLong(FileKey::millis).thenComparing(FileKey::path);

    @Override
    public SourceType type() {
        return SourceType.LOCAL_FS;
    }

    @Override
    public boolean hasDynamicIterables() {
        return true; // new sub-directories can appear under the root after activation
    }

    @Override
    public SyncSchedule defaultSchedule() {
        // A filesystem has no push/change-feed, so incremental sync means re-walking the tree
        // (see the forward-pagination notes above). That is relatively expensive, so the
        // connector-level default is a gentle once-a-day re-arm; users wanting fresher sync can set
        // a custom schedule on the knowledge, and webhooks/manual sync still trigger immediately.
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
        // The window's shape is the sense of the walk: a lower-bounded window is the forward
        // (mtime >= anchor) walk, an upper-bounded one the backfill (mtime < anchor). The bound itself
        // is the anchor, so we never branch on a direction enum.
        TimeWindow window = ctx.seedWindow();
        return window.hasLo()
                ? grabForward(dir, recursive, window.lo(), position, cap)
                : grabBackward(dir, recursive, window.hi(), position, cap);
    }

    // ---- forward: mtime order, single streaming pass + bounded heap --------------------------

    /**
     * Selects the next {@code cap} files with {@code mtime >= anchor} that sort after the cursor,
     * in ascending {@code (mtime, path)} order. One pass over the subtree feeds a bounded max-heap
     * (the largest of the {@code cap} smallest sits on top and is evicted when a smaller key
     * arrives), so we never sort or hold the whole subtree.
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
                    // skip unreadable file
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk directory " + dir, e);
        }
    }

    // ---- backward: path order, cursor-skipping DFS with early stop ---------------------------

    /**
     * Pages history in path order. The cursor is the last returned file's path; the DFS uses it to
     * skip every entry already consumed (siblings sorted before the cursor, and the whole spine the
     * cursor descended) and stops as soon as {@code cap + 1} files are collected — so each page
     * touches only the cursor's path plus the next {@code cap} files, never the whole subtree again.
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

    /** Cursor path relative to {@code dir}, split into name components, or {@code null} to start. */
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
     * Depth-first walk that emits files in component-wise path order, resuming after a cursor and
     * short-circuiting once {@code limit} files are collected. {@code found} holds at most
     * {@code limit} keys (= {@code cap + 1}, so the caller can detect {@code hasMore}).
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

        /** @return true once {@code limit} files are collected (signals callers to stop). */
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
                        // on the cursor's spine: recurse still-on-spine into the matching directory;
                        // a matching file is the cursor itself (or a prefix) and is skipped.
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
                return false; // skip unreadable file
            }
            if (!Instant.ofEpochMilli(millis).isBefore(anchor)) {
                return false; // backward window is mtime < anchor
            }
            found.add(new FileKey(millis, file.toAbsolutePath().normalize().toString()));
            return found.size() >= limit;
        }
    }

    // ---- pagination state (source-defined) ---------------------------------------------------

    private static CursorPosition encode(FileKey k) {
        return CursorPosition.builder()
                .put(POS_MILLIS, k.millis())
                .put(POS_PATH, k.path())
                .build();
    }

    private static FileKey decode(CursorPosition position) {
        return new FileKey(position.getLong(POS_MILLIS, 0L), position.getString(POS_PATH));
    }

    // ---- mapping & helpers -------------------------------------------------------------------

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
     * Cheap change-detection token from {@code (size, lastModified)} — an O(1) stat, no byte reads.
     * Any normal edit changes the modified time (and usually the size), so the checksum changes and
     * the entity is re-indexed. This mirrors the quick-check used by rsync/most file-sync tools and
     * aligns with the mtime-ordered grabber (a modified file is re-grabbed because its mtime moved).
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

    private static String probeContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type != null ? type : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /** Ordering key: last-modified millis + absolute path (for deterministic, stable paging). */
    record FileKey(long millis, String path) {
    }
}
