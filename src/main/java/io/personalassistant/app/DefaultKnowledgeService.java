package io.personalassistant.app;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Default knowledge lifecycle orchestration. On {@link #add} it verifies the connector, sets the
 * anchor to "now" (the boundary between backward backfill and forward incremental), discovers the
 * source's iterables, and creates one backward (if backfill is enabled) and one forward cursor per
 * iterable — then flips the knowledge to {@code ACTIVE} so the ingestion loop picks the cursors up.
 */
@ApplicationScoped
public class DefaultKnowledgeService implements KnowledgeService {

    private static final Logger LOG = Logger.getLogger(DefaultKnowledgeService.class.getName());

    private final KnowledgeRepository knowledge;
    private final CursorRepository cursors;
    private final EntityRepository entities;
    private final ConnectorRegistry connectors;
    private final SearchIndex index;

    @Inject
    public DefaultKnowledgeService(KnowledgeRepository knowledge, CursorRepository cursors,
                                   EntityRepository entities, ConnectorRegistry connectors,
                                   SearchIndex index) {
        this.knowledge = knowledge;
        this.cursors = cursors;
        this.entities = entities;
        this.connectors = connectors;
        this.index = index;
    }

    @Override
    public Knowledge add(NewKnowledge request) {
        Instant now = Instant.now();
        Knowledge.Config config = request.config() != null ? request.config() : Knowledge.Config.defaults();
        Knowledge draft = new Knowledge(
                Ids.knowledge(),
                request.name(),
                new Knowledge.ConnectorDetails(request.type(), request.auth() == null ? java.util.Map.of() : request.auth()),
                request.inputs() == null ? java.util.Map.of() : request.inputs(),
                config,
                now,
                KnowledgeStatus.DRAFT,
                Knowledge.Stats.zero(),
                now,
                now);

        SourceConnector connector = connectors.get(request.type());
        connector.verify(draft);              // throws on bad credentials/inputs
        knowledge.save(draft);                // persist as DRAFT first

        createCursors(connector, draft);
        knowledge.updateStatus(draft.id(), KnowledgeStatus.ACTIVE);
        LOG.info("Activated knowledge " + draft.id() + " (" + request.type() + ")");
        return knowledge.findById(draft.id()).orElse(draft);
    }

    @Override
    public Optional<Knowledge> get(String id) {
        return knowledge.findById(id);
    }

    @Override
    public List<Knowledge> list() {
        return knowledge.findAll();
    }

    @Override
    public void pause(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.PAUSED);
    }

    @Override
    public void resume(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.ACTIVE);
    }

    @Override
    public void delete(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.DELETED); // stop scheduling immediately
        index.deleteByKnowledge(id);                          // remove derived chunks
        entities.deleteByKnowledge(id);                       // remove canonical entities
        cursors.deleteByKnowledge(id);                        // remove cursors
        knowledge.delete(id);                                 // finally drop the knowledge record
    }

    @Override
    public int triggerSync(String id) {
        return cursors.armForwardCursors(id);
    }

    private void createCursors(SourceConnector connector, Knowledge kn) {
        List<SourceIterable> iterables = connector.discover(kn);
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        for (SourceIterable iterable : iterables) {
            if (backfill) {
                cursors.insertIfAbsent(newCursor(kn, iterable.iterableId(), CursorDirection.BACKWARD));
            }
            cursors.insertIfAbsent(newCursor(kn, iterable.iterableId(), CursorDirection.FORWARD));
        }
    }

    private Cursor newCursor(Knowledge kn, String iterableId, CursorDirection direction) {
        return new Cursor(
                Ids.cursorFor(kn.id(), iterableId, direction.name()),
                kn.id(),
                iterableId,
                direction,
                null,
                CursorStatus.AVAILABLE,
                null,
                Cursor.Retry.zero(),
                Cursor.Stats.zero(),
                new Cursor.Scope(kn.connectorDetails().type()));
    }
}
