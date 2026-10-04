package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StubConnector implements SourceConnector {

    private final SourceType type;
    private final List<SourceIterable> iterables;
    private final Map<CursorDirection, Deque<GrabResult>> pages = new EnumMap<>(CursorDirection.class);
    private RuntimeException failure;
    private RuntimeException discoverFailure;
    private boolean dynamicIterables;
    private SyncSchedule defaultSchedule = SyncSchedule.NONE;
    private Optional<Duration> defaultRetention = Optional.empty();
    private Set<String> membershipKeys; // null = signature hashes the whole inputs map
    private boolean requiresConnection;
    private RuntimeException verifyConnectionFailure;
    private ReindexMode reindexMode = ReindexMode.REINDEX_ONLY;
    private final Map<String, Optional<RawItem>> fetchOneResults = new LinkedHashMap<>();

    public int discoverCalls;
    public int verifyCalls;
    public int verifyConnectionCalls;
    public String lastGrabIterableId;
    public Map<String, Object> lastGrabAttributes;
    public int materializeCalls;
    public final List<String> fetchOneCalls = new ArrayList<>();

    public StubConnector(SourceType type, List<SourceIterable> iterables) {
        this.type = type;
        this.iterables = new ArrayList<>(iterables);
    }

    public StubConnector enqueue(CursorDirection direction, GrabResult page) {
        pages.computeIfAbsent(direction, d -> new ArrayDeque<>()).add(page);
        return this;
    }

    public StubConnector withReindexMode(ReindexMode mode) {
        this.reindexMode = mode;
        return this;
    }

    /** An id with no entry is gone at the source. */
    public StubConnector withFetchOne(String externalId, RawItem item) {
        fetchOneResults.put(externalId, Optional.ofNullable(item));
        return this;
    }

    public StubConnector failNext(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public StubConnector failDiscoveryWith(RuntimeException failure) {
        this.discoverFailure = failure;
        return this;
    }

    public StubConnector addIterable(SourceIterable iterable) {
        iterables.add(iterable);
        return this;
    }

    public StubConnector removeIterable(String iterableId) {
        iterables.removeIf(it -> it.iterableId().equals(iterableId));
        return this;
    }

    public StubConnector withDynamicIterables(boolean dynamic) {
        this.dynamicIterables = dynamic;
        return this;
    }

    public StubConnector withRequiresConnection(boolean requires) {
        this.requiresConnection = requires;
        return this;
    }

    public StubConnector failVerifyConnectionWith(RuntimeException failure) {
        this.verifyConnectionFailure = failure;
        return this;
    }

    public StubConnector withDefaultSchedule(SyncSchedule schedule) {
        this.defaultSchedule = schedule == null ? SyncSchedule.NONE : schedule;
        return this;
    }

    public StubConnector withMembershipKeys(String... keys) {
        this.membershipKeys = new LinkedHashSet<>(Arrays.asList(keys));
        return this;
    }

    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        Map<String, Object> src = inputs == null ? Map.of() : inputs;
        if (membershipKeys == null) {
            return String.valueOf(src);
        }
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String key : membershipKeys) {
            if (src.containsKey(key)) {
                subset.put(key, src.get(key));
            }
        }
        return String.valueOf(subset);
    }

    @Override
    public boolean hasDynamicIterables() {
        return dynamicIterables;
    }

    public StubConnector withDefaultRetention(Duration retention) {
        this.defaultRetention = Optional.ofNullable(retention);
        return this;
    }

    @Override
    public Optional<Duration> defaultRetention() {
        return defaultRetention;
    }

    @Override
    public SyncSchedule defaultSchedule() {
        return defaultSchedule;
    }

    @Override
    public SourceType type() {
        return type;
    }

    @Override
    public boolean requiresConnection() {
        return requiresConnection;
    }

    @Override
    public void verifyConnection(Connection connection) {
        verifyConnectionCalls++;
        if (verifyConnectionFailure != null) {
            throw verifyConnectionFailure;
        }
    }

    @Override
    public void verify(Knowledge knowledge) {
        verifyCalls++;
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        discoverCalls++;
        if (discoverFailure != null) {
            throw discoverFailure;
        }
        return new ArrayList<>(iterables);
    }

    @Override
    public Entity.Content materialize(Knowledge knowledge, RawItem item) {
        materializeCalls++;
        return SourceConnector.super.materialize(knowledge, item);
    }

    @Override
    public ReindexMode defaultReindexMode() {
        return reindexMode;
    }

    @Override
    public Optional<RawItem> fetchOne(Knowledge knowledge, Entity entity) {
        fetchOneCalls.add(entity.externalId());
        return fetchOneResults.getOrDefault(entity.externalId(), Optional.empty());
    }

    @Override
    public GrabResult grab(GrabContext ctx) {
        lastGrabIterableId = ctx.iterableId();
        lastGrabAttributes = ctx.attributes();
        if (failure != null) {
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
        // Direction isn't passed: a lower-bounded seed window is forward, an upper-bounded one backward.
        CursorDirection direction = ctx.seedWindow().hasLo()
                ? CursorDirection.FORWARD : CursorDirection.BACKWARD;
        Deque<GrabResult> queue = pages.get(direction);
        if (queue == null || queue.isEmpty()) {
            return GrabResult.end(ctx.cursor());
        }
        return queue.poll();
    }
}
