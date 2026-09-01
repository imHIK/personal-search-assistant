package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base for applicant-tracking job boards, which are <strong>snapshot-shaped</strong>: one request
 * returns the entire current board. Greenhouse, Lever and Ashby all work this way — no
 * {@code updated_after} parameter, no continuation token, no meaningful pagination — so neither
 * {@code TokenWindowGrabber} nor {@code TimeWindowGrabber} fits and these implement
 * {@link SourceConnector} directly, as {@code LocalFsConnector} does.
 *
 * <h2>What the framework machinery still buys us</h2>
 * The seed {@link io.personalassistant.ingestion.connector.TimeWindow} is ignored — there is nothing
 * to filter server-side — and every grab returns the whole board in one page with
 * {@code hasMore=false}. That sounds like the incremental machinery is wasted, but the expensive half
 * still works: change detection in {@code IngestionRunner.persistItem} skips any posting whose
 * checksum is unchanged and is already {@code INDEXED}, so a poll costs one HTTP call plus N cheap
 * Mongo lookups and re-indexes only what actually moved.
 *
 * <h2>Forward-only, and why</h2>
 * Backward cursors exist to walk history below the anchor. A job board has no history worth walking —
 * a posting old enough to sit below the anchor is filled or withdrawn — so
 * {@link #supportedDirections()} declares {@code FORWARD} only and no backward cursor is ever created.
 *
 * <h2>Disappearance</h2>
 * A closed posting simply stops appearing in the snapshot; boards send no tombstone. That is handled
 * by retention rather than by diffing snapshots here, which is why these connectors are the ones that
 * override {@link #defaultRetention()}. See {@code docs/knowledge-lifecycle.md}.
 */
public abstract class SnapshotBoardConnector implements SourceConnector {

    /** {@code inputs} key holding the board identifiers to follow. */
    public static final String BOARDS_INPUT = "boards";

    /** Iterable attribute carrying one board's identifier through to {@link #grab}. */
    public static final String BOARD_ATTRIBUTE = "board";

    /** Cursor field: when the last complete snapshot was taken. Observability only — grabs are stateless. */
    private static final String POS_SNAPSHOT_AT = "snapshotAtMillis";

    /**
     * Poll cadence. Boards change on a human timescale (a recruiter publishing a role), so polling
     * far more often than this only burns requests without surfacing anything sooner.
     */
    private static final Duration POLL_INTERVAL = Duration.ofHours(3);

    /**
     * Retention window. Comfortably longer than the poll interval — an item is re-created by the next
     * walk if it still exists at the source, so a short window would just churn re-embeddings — and
     * short enough that a filled role does not linger in search for months.
     */
    private static final Duration RETENTION = Duration.ofDays(14);

    /** Fetch every posting currently on one board. Called once per grab; must return the full set. */
    protected abstract List<RawItem> fetchBoard(String boardId);

    /**
     * Check that a board identifier resolves at the source.
     *
     * @throws RuntimeException if the board does not exist or the API is unreachable
     */
    protected abstract void verifyBoard(String boardId);

    /** Human-readable name for a board, used as the iterable's display name. */
    protected String displayName(String boardId) {
        return boardId;
    }

    @Override
    public Set<CursorDirection> supportedDirections() {
        return EnumSet.of(CursorDirection.FORWARD);
    }

    @Override
    public boolean hasDynamicIterables() {
        // The board list is user-supplied, not discovered, so it only changes on an explicit edit —
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

    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        // The board list is a discovery-set dimension (each board is its own iterable), so adding or
        // removing one is handled by discover-reconcile, not by re-walking the survivors. Returning a
        // constant keeps an edit from pointlessly resetting every existing board's cursor.
        return "";
    }

    @Override
    public void verify(Knowledge knowledge) {
        List<String> boards = boards(knowledge);
        if (boards.isEmpty()) {
            throw new IllegalArgumentException(
                    "inputs." + BOARDS_INPUT + " must list at least one board identifier");
        }
        for (String board : boards) {
            verifyBoard(board);
        }
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        List<SourceIterable> iterables = new ArrayList<>();
        for (String board : boards(knowledge)) {
            iterables.add(new SourceIterable(board, displayName(board), Map.of(BOARD_ATTRIBUTE, board)));
        }
        return iterables;
    }

    @Override
    public GrabResult grab(GrabContext context) {
        String board = context.attributes().get(BOARD_ATTRIBUTE) instanceof String s && !s.isBlank()
                ? s
                : context.iterableId(); // cursors created before attributes were snapshotted
        List<RawItem> items = fetchBoard(board);
        CursorPosition position = context.cursor().toBuilder()
                .put(POS_SNAPSHOT_AT, Instant.now().toEpochMilli())
                .build();
        // One snapshot is the whole board, so there is never a second page. The runner maps
        // hasMore=false on a forward cursor to IDLE, which is exactly right: nothing more to do
        // until the schedule re-arms.
        return new GrabResult(items, position, false);
    }

    /**
     * The configured board identifiers, de-duplicated and order-preserving. Accepts a list or a single
     * string so a one-board knowledge can be configured without an array.
     */
    protected static List<String> boards(Knowledge knowledge) {
        Object raw = knowledge.inputs() == null ? null : knowledge.inputs().get(BOARDS_INPUT);
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof String s) {
            addIfPresent(out, s);
        } else if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                addIfPresent(out, value == null ? null : value.toString());
            }
        }
        return List.copyOf(out);
    }

    private static void addIfPresent(Set<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.trim());
        }
    }
}
