package io.personalassistant.app;

import io.personalassistant.common.Errors;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.EntityQuery;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.service.KnowledgePatch;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.domain.service.Patched;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.DiscoveryStatusRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class DefaultKnowledgeService implements KnowledgeService {

    private static final Logger LOG = Logger.getLogger(DefaultKnowledgeService.class.getName());

    private final KnowledgeRepository knowledge;
    private final CursorRepository cursors;
    private final EntityRepository entities;
    private final ConnectorRegistry connectors;
    private final ConnectionResolver connections;
    private final SearchIndex index;
    private final DiscoveryStatusRepository discoveryStatus;
    private final RefetchPolicy refetchPolicy;

    @Inject
    public DefaultKnowledgeService(KnowledgeRepository knowledge, CursorRepository cursors,
                                   EntityRepository entities, ConnectorRegistry connectors,
                                   ConnectionResolver connections, SearchIndex index,
                                   DiscoveryStatusRepository discoveryStatus,
                                   RefetchPolicy refetchPolicy) {
        this.knowledge = knowledge;
        this.cursors = cursors;
        this.entities = entities;
        this.connectors = connectors;
        this.connections = connections;
        this.index = index;
        this.discoveryStatus = discoveryStatus;
        this.refetchPolicy = refetchPolicy;
    }

    private void verifyConnectionIfRequired(SourceConnector connector, Knowledge kn) {
        if (connector.requiresConnection()) {
            Connection connection = connections.resolve(kn);
            connector.verifyConnection(connection);
        }
    }

    @Override
    public Knowledge add(NewKnowledge request) {
        Instant now = Instant.now();
        Knowledge.Config config = request.config() != null ? request.config() : Knowledge.Config.defaults();
        // Checked before the DRAFT is persisted: a bad cron is a 400, not a source parked in ERROR.
        if (config.scheduleSettings() != null) {
            ScheduleResolver.requireValidCron(config.scheduleSettings().cron());
        }
        Knowledge draft = new Knowledge(
                Ids.knowledge(),
                request.name(),
                new Knowledge.ConnectorDetails(request.type(), request.connectionId(),
                        request.auth() == null ? java.util.Map.of() : request.auth()),
                request.inputs() == null ? java.util.Map.of() : request.inputs(),
                config,
                now,
                null, // nextSyncDueAt: null is due now
                KnowledgeStatus.DRAFT,
                null,
                Knowledge.Stats.zero(),
                now,
                now,
                0L); // syncGeneration
        knowledge.save(draft); // a DRAFT first, so any activation failure is recorded against a real record

        // Any failure below parks the knowledge in ERROR with the reason rather than throwing it away.
        try {
            SourceConnector connector = connectors.get(request.type());
            verifyConnectionIfRequired(connector, draft);
            connector.verify(draft);
            List<SourceIterable> iterables = discover(draft, DiscoveryTrigger.ACTIVATION);
            DirCounts created = createCursors(draft, iterables);
            recordDiscovery(draft, DiscoveryTrigger.ACTIVATION, iterables.size(),
                    created, DirCounts.zero(), DirCounts.zero());
            knowledge.updateStatus(draft.id(), KnowledgeStatus.ACTIVE);
            LOG.info("Activated knowledge " + draft.id() + " (" + request.type() + ")");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate knowledge " + draft.id()
                    + " (" + request.type() + "); marking ERROR", e);
            knowledge.markError(draft.id(), Errors.summary(e));
        }
        return knowledge.findById(draft.id()).orElse(draft);
    }

    /**
     * Routes by what changed: config fields are one in-place write; auth, inputs and backfill-on re-verify,
     * re-discover and reconcile.
     */
    @Override
    public Knowledge update(String id, KnowledgePatch patch) {
        Knowledge current = knowledge.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No knowledge with id " + id));

        if (current.status() == KnowledgeStatus.DELETED) {
            throw new IllegalStateException("A DELETED knowledge cannot be edited");
        }
        if (patch.type().present() && patch.type().value() != current.connectorDetails().type()) {
            throw new IllegalArgumentException(
                    "connectorDetails.type is immutable; delete and recreate to change connector");
        }
        // Only a cron this edit sends is checked: a cron stored before validation existed must not block the
        // edit that fixes it.
        if (patch.schedule().cron().present()) {
            ScheduleResolver.requireValidCron(patch.schedule().cron().value());
        }

        boolean authChanged = patch.auth().present() && patch.auth().value() != null
                && !patch.auth().value().equals(current.connectorDetails().auth());
        boolean inputsChanged = patch.inputs().present() && patch.inputs().value() != null
                && !patch.inputs().value().equals(current.inputs());
        boolean backfillOn = current.config().backfill() != null && current.config().backfill().enabled();
        boolean backfillTurnedOn = flag(patch.backfillEnabled(), backfillOn) && !backfillOn;

        Knowledge updated = applyPatch(current, patch, Instant.now());

        boolean provisioning = authChanged || inputsChanged || backfillTurnedOn;
        if (!provisioning) {
            return applyConfigEdit(current, updated);
        }

        boolean membershipChanged = inputsChanged && membershipSignatureChanged(current, updated);
        return reprovision(current, updated, membershipChanged);
    }

    private Knowledge applyPatch(Knowledge current, KnowledgePatch patch, Instant now) {
        Map<String, Object> auth = orCurrent(patch.auth(), current.connectorDetails().auth());
        Knowledge.ConnectorDetails cd = new Knowledge.ConnectorDetails(
                current.connectorDetails().type(),
                current.connectorDetails().connectionId(), // the connection binding is not editable
                auth);

        Knowledge.Config cur = current.config();
        Knowledge.ScheduleSettings schedule = new Knowledge.ScheduleSettings(
                // A null cron is a real instruction: it moves a source off a custom schedule back onto an
                // interval.
                patch.schedule().cron().orElse(cur.scheduleSettings().cron()),
                patch.schedule().interval().orElse(cur.scheduleSettings().interval()),
                flag(patch.schedule().enabled(), cur.scheduleSettings().enabled()));
        Knowledge.WebhookSettings webhook = new Knowledge.WebhookSettings(
                flag(patch.webhook().enabled(), cur.webhookSettings().enabled()),
                patch.webhook().secret().orElse(cur.webhookSettings().secret()));
        Knowledge.Backfill backfill = new Knowledge.Backfill(
                flag(patch.backfillEnabled(), cur.backfill().enabled()));

        // No re-chunk: only new chunks use the settings.
        Knowledge.ChunkingSettings curChunk = cur.chunking();
        Knowledge.ChunkingSettings chunking = new Knowledge.ChunkingSettings(
                patch.chunking().strategy().orElse(curChunk.strategy()),
                patch.chunking().maxSize().orElse(curChunk.maxSize()),
                patch.chunking().overlap().orElse(curChunk.overlap()),
                patch.chunking().separators().orElse(curChunk.separators()));

        Knowledge.Retention retention = new Knowledge.Retention(
                patch.retentionPeriod().orElse(cur.retention().period()));

        Knowledge.Config config = new Knowledge.Config(schedule, webhook, backfill, chunking, retention);

        return current.withEdits(orCurrent(patch.name(), current.name()), cd,
                orCurrent(patch.inputs(), current.inputs()), config, now);
    }

    private static boolean flag(Patched<Boolean> patched, boolean current) {
        Boolean value = patched.orElse(current);
        return value == null ? current : value;
    }

    private static <T> T orCurrent(Patched<T> patched, T current) {
        T value = patched.orElse(current);
        return value == null ? current : value;
    }

    private static boolean cadenceChanged(Knowledge a, Knowledge b) {
        Knowledge.ScheduleSettings sa = a.config().scheduleSettings();
        Knowledge.ScheduleSettings sb = b.config().scheduleSettings();
        return !Objects.equals(sa.cron(), sb.cron()) || !Objects.equals(sa.interval(), sb.interval());
    }

    private boolean membershipSignatureChanged(Knowledge current, Knowledge updated) {
        SourceConnector connector = connectors.get(current.connectorDetails().type());
        return !Objects.equals(
                connector.membershipSignature(current.inputs()),
                connector.membershipSignature(updated.inputs()));
    }

    private Knowledge applyConfigEdit(Knowledge current, Knowledge updated) {
        Knowledge toSave = updated;
        // Clearing nextSyncDueAt (null is due now) makes ForwardCursorScheduler re-resolve the cadence on its
        // next tick.
        if (cadenceChanged(current, updated)) {
            toSave = toSave.withNextSyncDueAt(null);
        }
        knowledge.save(toSave);

        if (!current.config().scheduleSettings().enabled()
                && updated.config().scheduleSettings().enabled()) {
            triggerSync(current.id());
        }
        return get(current.id()).orElse(toSave);
    }

    /**
     * The anchor never moves here: forward stays >= anchor and backward < anchor, so the edit opens no gap or
     * overlap.
     */
    private Knowledge reprovision(Knowledge current, Knowledge updated, boolean membershipChanged) {
        String id = current.id();
        KnowledgeStatus original = current.status();

        // Pause first so nothing is leased mid-change. The edited config is persisted now, so a failed verify
        // below lands in ERROR without losing what was submitted.
        cursors.suspendByKnowledge(id);
        Knowledge held = updated.withStatus(KnowledgeStatus.PAUSED);
        knowledge.save(held);

        try {
            SourceConnector connector = connectors.get(held.connectorDetails().type());
            verifyConnectionIfRequired(connector, held);
            connector.verify(held);
            List<SourceIterable> iterables = discover(held, DiscoveryTrigger.RECONCILE);

            DirCounts created = createCursors(held, iterables);
            DirCounts revived = reviveReappearedIterables(held, iterables);
            DirCounts parked = parkDisappearedIterables(held, iterables);
            refreshIterableNames(held, iterables);

            // Bump the generation and persist it before resetting cursors, so the re-walk stamps freshly-seen
            // entities with the new value and narrowed-out ones are left behind.
            Knowledge effective = held;
            if (membershipChanged) {
                effective = held.bumpGeneration().withStatus(KnowledgeStatus.PAUSED);
                knowledge.save(effective);
                // Flag stale staged copies before the rewind: a cursor that walks first would skip them on an
                // unchanged checksum.
                if (refetchPolicy.refetches(effective)) {
                    int flagged = entities.flagNeedsRefetchByKnowledge(id);
                    if (flagged > 0) {
                        LOG.info("Membership re-walk for knowledge " + id + " will also re-fetch "
                                + flagged + " staged file(s)");
                    }
                }
                rewalkForMembershipChange(effective, iterables);
            }

            recordDiscovery(effective, DiscoveryTrigger.RECONCILE, iterables.size(),
                    created, revived, parked);

            // A PAUSED knowledge stays parked, new cursors included; anything else ends ACTIVE with its
            // cursors re-armed.
            if (original == KnowledgeStatus.PAUSED) {
                cursors.suspendByKnowledge(id);
            } else {
                knowledge.updateStatus(id, KnowledgeStatus.ACTIVE);
                cursors.resumeByKnowledge(id);
            }
            LOG.info("Re-provisioned knowledge " + id + " (+" + created.total() + " new, "
                    + revived.total() + " revived, " + parked.total() + " parked"
                    + (membershipChanged ? ", membership re-walk" : "") + ")");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Re-provision failed for knowledge " + id + "; marking ERROR", e);
            knowledge.markError(id, Errors.summary(e));
        }
        return get(id).orElse(updated);
    }

    /**
     * Unlike the discovery reconcile, parks without purging: an edit cannot tell an intentional narrowing
     * from an accidental scope drop. RETIRED rather than SUSPENDED keeps them out of the end-of-flow resume
     * and lets revive bring them back.
     */
    private DirCounts parkDisappearedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Set<String> liveIds = new HashSet<>();
        iterables.forEach(it -> liveIds.add(it.iterableId()));
        int backward = 0;
        int forward = 0;
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            if (!liveIds.contains(c.iterableId())
                    && c.status() != CursorStatus.RETIRED && c.status() != CursorStatus.IN_PROGRESS
                    && cursors.retire(c.id())) {
                if (c.direction() == CursorDirection.BACKWARD) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }
        return new DirCounts(backward, forward);
    }

    /**
     * Rewinds both cursors of every live iterable; unchanged items are skipped and only re-stamped. With
     * backfill off there is no backward cursor, so items below the anchor are not re-covered.
     */
    private void rewalkForMembershipChange(Knowledge kn, List<SourceIterable> iterables) {
        Set<String> liveIds = new HashSet<>();
        iterables.forEach(it -> liveIds.add(it.iterableId()));
        int reset = 0;
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            if (liveIds.contains(c.iterableId()) && cursors.resetToStart(c.id())) {
                reset++;
            }
        }
        if (reset > 0) {
            LOG.info("Membership re-walk for knowledge " + kn.id() + ": reset " + reset
                    + " cursor(s) to start");
        }
    }

    @Override
    public Optional<Knowledge> get(String id) {
        return knowledge.findById(id).map(this::withFreshStats);
    }

    @Override
    public List<Knowledge> list() {
        return knowledge.findAll().stream().map(this::withFreshStats).toList();
    }

    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 200;

    @Override
    public EntityPage listEntities(String id, EntityQuery query, int limit, int offset) {
        requireExists(id);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        int size = limit <= 0 ? DEFAULT_PAGE : Math.min(limit, MAX_PAGE);
        EntityQuery effective = query == null ? EntityQuery.all() : query;
        long total = entities.countByKnowledge(id, effective);
        return new EntityPage(entities.findByKnowledge(id, effective, size, offset), total, size, offset);
    }

    @Override
    public List<Cursor> listCursors(String id) {
        requireExists(id);
        return cursors.findByKnowledge(id).stream()
                .sorted(Comparator.comparing(Cursor::iterableId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Cursor::direction, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void requireExists(String id) {
        if (knowledge.findById(id).isEmpty()) {
            throw new NoSuchElementException("No knowledge with id " + id);
        }
    }

    /** Computed on read rather than maintained on the hot ingestion and indexing path. */
    private Knowledge withFreshStats(Knowledge kn) {
        long total = entities.countByKnowledge(kn.id());
        long indexed = entities.countByKnowledgeAndStatus(kn.id(), EntityStatus.INDEXED);
        long failed = entities.countByKnowledgeAndStatus(kn.id(), EntityStatus.FAILED);
        return kn.withStats(new Knowledge.Stats(total, indexed, failed));
    }

    @Override
    public void pause(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.PAUSED);
        cursors.suspendByKnowledge(id);
    }

    @Override
    public void resume(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.ACTIVE);
        cursors.resumeByKnowledge(id);
    }

    @Override
    public void delete(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.DELETED); // first, so nothing is scheduled mid-cascade
        index.deleteByKnowledge(id);
        entities.deleteByKnowledge(id);
        cursors.deleteByKnowledge(id);
        discoveryStatus.deleteByKnowledge(id);
        knowledge.delete(id);
    }

    @Override
    public int triggerSync(String id) {
        return cursors.armForwardCursors(id);
    }

    @Override
    public int reconcileCursors(String id) {
        Optional<Knowledge> known = knowledge.findById(id);
        if (known.isEmpty() || known.get().status() != KnowledgeStatus.ACTIVE) {
            return 0;
        }
        Knowledge kn = known.get();
        List<SourceIterable> iterables = discover(kn, DiscoveryTrigger.RECONCILE);

        DirCounts created = createCursors(kn, iterables);
        DirCounts revived = reviveReappearedIterables(kn, iterables);
        DirCounts retired = retireDeletedIterables(kn, iterables);
        refreshIterableNames(kn, iterables);

        recordDiscovery(kn, DiscoveryTrigger.RECONCILE, iterables.size(), created, revived, retired);

        if (created.total() > 0 || revived.total() > 0 || retired.total() > 0) {
            LOG.info("Reconcile for knowledge " + id + ": +" + created.total() + " new, "
                    + revived.total() + " revived, " + retired.total() + " retired cursor(s)");
        }
        return created.total();
    }

    /** Names are cosmetic, so this write is unfenced: losing a race to a worker costs nothing. */
    private void refreshIterableNames(Knowledge kn, List<SourceIterable> iterables) {
        Map<String, String> names = new HashMap<>();
        iterables.forEach(it -> names.put(it.iterableId(), it.displayName()));
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            String name = names.get(c.iterableId());
            if (name != null && !name.equals(c.iterableName())) {
                cursors.rename(c.id(), name);
            }
        }
    }

    private DirCounts reviveReappearedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Map<String, Map<String, Object>> live = new HashMap<>();
        iterables.forEach(it -> live.put(it.iterableId(), it.attributes()));
        int backward = 0;
        int forward = 0;
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            if (c.status() == CursorStatus.RETIRED && live.containsKey(c.iterableId())
                    && cursors.revive(c.id(), live.get(c.iterableId()))) {
                if (c.direction() == CursorDirection.BACKWARD) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }
        return new DirCounts(backward, forward);
    }

    /** Idempotent: retired and running cursors are skipped and caught on a later pass. */
    private DirCounts retireDeletedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Set<String> liveIds = new HashSet<>();
        iterables.forEach(it -> liveIds.add(it.iterableId()));
        List<Cursor> all = cursors.findByKnowledge(kn.id());

        Set<String> goneIterables = new LinkedHashSet<>();
        for (Cursor c : all) {
            if (!liveIds.contains(c.iterableId())
                    && c.status() != CursorStatus.RETIRED && c.status() != CursorStatus.IN_PROGRESS) {
                goneIterables.add(c.iterableId());
            }
        }
        for (String iterableId : goneIterables) {
            index.deleteByIterable(kn.id(), iterableId);
            entities.deleteByKnowledgeAndIterable(kn.id(), iterableId);
        }
        int backward = 0;
        int forward = 0;
        for (Cursor c : all) {
            if (goneIterables.contains(c.iterableId()) && cursors.retire(c.id())) {
                if (c.direction() == CursorDirection.BACKWARD) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }
        return new DirCounts(backward, forward);
    }

    private List<SourceIterable> discover(Knowledge kn, DiscoveryTrigger trigger) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());
        try {
            return connector.discover(kn);
        } catch (RuntimeException e) {
            String error = Errors.summary(e);
            for (CursorDirection direction : activeGrabberDirections(kn)) {
                discoveryStatus.record(DiscoveryStatus.Run.failed(kn.id(), direction, trigger, error));
            }
            throw e;
        }
    }

    private void recordDiscovery(Knowledge kn, DiscoveryTrigger trigger, int iterablesFound,
                                 DirCounts created, DirCounts revived, DirCounts retired) {
        for (CursorDirection direction : activeGrabberDirections(kn)) {
            discoveryStatus.record(DiscoveryStatus.Run.ok(kn.id(), direction, trigger, iterablesFound,
                    new DiscoveryStatus.Counts(created.of(direction), revived.of(direction),
                            retired.of(direction))));
        }
    }

    /** Must mirror the cursors {@link #createCursors} makes. */
    private List<CursorDirection> activeGrabberDirections(Knowledge kn) {
        var supported = connectors.get(kn.connectorDetails().type()).supportedDirections();
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        List<CursorDirection> directions = new ArrayList<>();
        if (supported.contains(CursorDirection.FORWARD)) {
            directions.add(CursorDirection.FORWARD);
        }
        if (backfill && supported.contains(CursorDirection.BACKWARD)) {
            directions.add(CursorDirection.BACKWARD);
        }
        return directions;
    }

    /**
     * Idempotent: deterministic cursor ids and insertIfAbsent only add cursors for iterables that are new.
     */
    private DirCounts createCursors(Knowledge kn, List<SourceIterable> iterables) {
        var supported = connectors.get(kn.connectorDetails().type()).supportedDirections();
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        int backward = 0;
        int forward = 0;
        for (SourceIterable iterable : iterables) {
            if (backfill && supported.contains(CursorDirection.BACKWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.BACKWARD))) {
                backward++;
            }
            if (supported.contains(CursorDirection.FORWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.FORWARD))) {
                forward++;
            }
        }
        return new DirCounts(backward, forward);
    }

    private record DirCounts(int backward, int forward) {
        static DirCounts zero() {
            return new DirCounts(0, 0);
        }

        int of(CursorDirection direction) {
            return direction == CursorDirection.BACKWARD ? backward : forward;
        }

        int total() {
            return backward + forward;
        }
    }

    private Cursor newCursor(Knowledge kn, SourceIterable iterable, CursorDirection direction) {
        return new Cursor(
                Ids.cursorFor(kn.id(), iterable.iterableId(), direction.name()),
                kn.id(),
                iterable.iterableId(),
                iterable.displayName(),
                iterable.attributes(),
                direction,
                CursorPosition.start(),
                CursorStatus.AVAILABLE,
                null,
                Cursor.Retry.zero(),
                Cursor.Stats.zero(),
                new Cursor.Scope(kn.connectorDetails().type()));
    }
}
