package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.GrabRequest;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scriptable {@link SourceConnector} for ingestion tests: queue pages per direction. */
public class StubConnector implements SourceConnector {

    private final SourceType type;
    private final List<SourceIterable> iterables;
    private final Map<CursorDirection, Deque<GrabPage>> pages = new EnumMap<>(CursorDirection.class);
    private RuntimeException failure;
    private RuntimeException discoverFailure;
    private boolean dynamicIterables;
    private SyncSchedule defaultSchedule = SyncSchedule.NONE;
    private Set<String> membershipKeys; // null = signature hashes the whole inputs map (default)
    private boolean requiresConnection;
    private RuntimeException verifyConnectionFailure;

    /** Test observability: how many times discover()/verify()/verifyConnection() ran, and the last iterable grabbed. */
    public int discoverCalls;
    public int verifyCalls;
    public int verifyConnectionCalls;
    public String lastGrabIterableId;
    public Map<String, Object> lastGrabAttributes;

    public StubConnector(SourceType type, List<SourceIterable> iterables) {
        this.type = type;
        this.iterables = new ArrayList<>(iterables);
    }

    public StubConnector enqueue(CursorDirection direction, GrabPage page) {
        pages.computeIfAbsent(direction, d -> new ArrayDeque<>()).add(page);
        return this;
    }

    public StubConnector failNext(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /** Make {@link #discover} throw, to exercise activation/reconcile failure handling. */
    public StubConnector failDiscoveryWith(RuntimeException failure) {
        this.discoverFailure = failure;
        return this;
    }

    /** Simulate a new iterable appearing at the source (for reconcile tests). */
    public StubConnector addIterable(SourceIterable iterable) {
        iterables.add(iterable);
        return this;
    }

    /** Simulate an iterable being deleted at the source (for reconcile-prune tests). */
    public StubConnector removeIterable(String iterableId) {
        iterables.removeIf(it -> it.iterableId().equals(iterableId));
        return this;
    }

    public StubConnector withDynamicIterables(boolean dynamic) {
        this.dynamicIterables = dynamic;
        return this;
    }

    /** Make this stub behave like a credentialed connector (needs a Connection). */
    public StubConnector withRequiresConnection(boolean requires) {
        this.requiresConnection = requires;
        return this;
    }

    /** Make {@link #verifyConnection} throw, to exercise create/verify failure handling. */
    public StubConnector failVerifyConnectionWith(RuntimeException failure) {
        this.verifyConnectionFailure = failure;
        return this;
    }

    /** Set the connector-level default schedule reported by {@link #defaultSchedule()}. */
    public StubConnector withDefaultSchedule(SyncSchedule schedule) {
        this.defaultSchedule = schedule == null ? SyncSchedule.NONE : schedule;
        return this;
    }

    /**
     * Restrict {@link #membershipSignature} to only these input keys, so tests can make a
     * membership-affecting change (touch a listed key) vs. a cosmetic one (touch any other key).
     */
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
    public GrabPage grab(GrabRequest request) {
        lastGrabIterableId = request.iterableId();
        lastGrabAttributes = request.attributes();
        if (failure != null) {
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
        Deque<GrabPage> queue = pages.get(request.direction());
        if (queue == null || queue.isEmpty()) {
            return GrabPage.end(request.position());
        }
        return queue.poll();
    }
}
