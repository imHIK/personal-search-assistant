package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.GrabRequest;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Scriptable {@link SourceConnector} for ingestion tests: queue pages per direction. */
public class StubConnector implements SourceConnector {

    private final SourceType type;
    private final List<SourceIterable> iterables;
    private final Map<CursorDirection, Deque<GrabPage>> pages = new EnumMap<>(CursorDirection.class);
    private RuntimeException failure;
    private boolean dynamicIterables;

    /** Test observability: how many times discover() was called, and the last iterable grabbed. */
    public int discoverCalls;
    public SourceIterable lastGrabIterable;

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

    @Override
    public boolean hasDynamicIterables() {
        return dynamicIterables;
    }

    @Override
    public SourceType type() {
        return type;
    }

    @Override
    public void verify(Knowledge knowledge) {
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        discoverCalls++;
        return new ArrayList<>(iterables);
    }

    @Override
    public GrabPage grab(GrabRequest request) {
        lastGrabIterable = request.iterable();
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
