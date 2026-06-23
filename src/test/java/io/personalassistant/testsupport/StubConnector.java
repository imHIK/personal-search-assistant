package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import java.util.ArrayDeque;
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

    public StubConnector(SourceType type, List<SourceIterable> iterables) {
        this.type = type;
        this.iterables = iterables;
    }

    public StubConnector enqueue(CursorDirection direction, GrabPage page) {
        pages.computeIfAbsent(direction, d -> new ArrayDeque<>()).add(page);
        return this;
    }

    public StubConnector failNext(RuntimeException failure) {
        this.failure = failure;
        return this;
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
        return iterables;
    }

    @Override
    public GrabPage grab(Knowledge knowledge, SourceIterable iterable, CursorDirection direction,
                         String position, int maxItems) {
        if (failure != null) {
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
        Deque<GrabPage> queue = pages.get(direction);
        if (queue == null || queue.isEmpty()) {
            return GrabPage.end(position);
        }
        return queue.poll();
    }
}
