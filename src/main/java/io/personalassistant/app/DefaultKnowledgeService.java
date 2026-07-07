package io.personalassistant.app;

import io.personalassistant.common.Errors;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.service.KnowledgePatch;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.DiscoveryStatusRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ConnectionResolver connections;
    private final SearchIndex index;
    private final DiscoveryStatusRepository discoveryStatus;

    @Inject
    public DefaultKnowledgeService(KnowledgeRepository knowledge, CursorRepository cursors,
                                   EntityRepository entities, ConnectorRegistry connectors,
                                   ConnectionResolver connections, SearchIndex index,
                                   DiscoveryStatusRepository discoveryStatus) {
        this.knowledge = knowledge;
        this.cursors = cursors;
        this.entities = entities;
        this.connectors = connectors;
        this.connections = connections;
        this.index = index;
        this.discoveryStatus = discoveryStatus;
    }

    /**
     * For connectors that authenticate through a {@link Connection}, resolve the bound (or default)
     * connection and verify its credentials before touching the source. Throws (→ knowledge parked in
     * {@code ERROR}) when no connection resolves or the credentials are rejected. A no-op for no-auth
     * connectors like {@code LOCAL_FS}.
     */
    private void verifyConnectionIfRequired(SourceConnector connector, Knowledge kn) {
        if (connector.requiresConnection()) {
            Connection connection = connections.resolve(kn); // missing/unknown connection → throws
            connector.verifyConnection(connection);          // bad credentials → throws
        }
    }

    @Override
    public Knowledge add(NewKnowledge request) {
        Instant now = Instant.now();
        Knowledge.Config config = request.config() != null ? request.config() : Knowledge.Config.defaults();
        Knowledge draft = new Knowledge(
                Ids.knowledge(),
                request.name(),
                new Knowledge.ConnectorDetails(request.type(), request.connectionId(),
                        request.auth() == null ? java.util.Map.of() : request.auth()),
                request.inputs() == null ? java.util.Map.of() : request.inputs(),
                config,
                now,
                null, // nextSyncDueAt: due now — the scheduler sets the first real due time on its next tick
                KnowledgeStatus.DRAFT,
                null,
                Knowledge.Stats.zero(),
                now,
                now,
                0L); // syncGeneration starts at 0; bumped on each membership-affecting edit
        knowledge.save(draft); // persist as DRAFT first, so any activation failure is recorded against a real record

        // Verify + discover + create cursors can all fail (bad credentials, unreachable source,
        // discovery error). Any failure parks the knowledge in ERROR with the reason captured, rather
        // than throwing it away — the user can see why it failed and retry.
        try {
            SourceConnector connector = connectors.get(request.type());
            verifyConnectionIfRequired(connector, draft);            // resolve + verify the connection
            connector.verify(draft);                                  // throws on bad inputs
            List<SourceIterable> iterables = discover(draft, DiscoveryTrigger.ACTIVATION); // throws if not enumerable
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
     * Edit an existing knowledge. Loads the stored record, diffs the patch against it, and routes by
     * <em>what actually changed</em> — because the two kinds of edit have completely different blast
     * radius. Config-class fields (name / schedule / webhook / backfill-off) are a single in-place
     * write; provisioning-class fields (auth / inputs / backfill-on) re-verify, re-discover, and
     * reconcile. See {@code knowledge-edit-design.md}.
     */
    @Override
    public Knowledge update(String id, KnowledgePatch patch) {
        Knowledge current = knowledge.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No knowledge with id " + id));

        // Two hard rules, independent of what changed.
        if (current.status() == KnowledgeStatus.DELETED) {
            throw new IllegalStateException("A DELETED knowledge cannot be edited");
        }
        if (patch.type().isPresent() && patch.type().get() != current.connectorDetails().type()) {
            throw new IllegalArgumentException(
                    "connectorDetails.type is immutable; delete and recreate to change connector");
        }

        // Classify the edit by diffing the patch against the stored record.
        boolean authChanged = patch.auth().isPresent()
                && !patch.auth().get().equals(current.connectorDetails().auth());
        boolean inputsChanged = patch.inputs().isPresent()
                && !patch.inputs().get().equals(current.inputs());
        boolean backfillOn = current.config().backfill() != null && current.config().backfill().enabled();
        boolean backfillTurnedOn = patch.backfillEnabled().orElse(backfillOn) && !backfillOn;

        Knowledge updated = applyPatch(current, patch, Instant.now());

        // auth / inputs / backfill-off→on can change WHAT is fetched and WHICH items belong, so they
        // run the re-provision pipeline. Everything else is an in-place config write.
        boolean provisioning = authChanged || inputsChanged || backfillTurnedOn;
        if (!provisioning) {
            return applyConfigEdit(current, updated);
        }

        boolean membershipChanged = inputsChanged && membershipSignatureChanged(current, updated);
        return reprovision(current, updated, membershipChanged);
    }

    /** Build the edited record by overlaying only the patch's present fields onto {@code current}. */
    private Knowledge applyPatch(Knowledge current, KnowledgePatch patch, Instant now) {
        Map<String, Object> auth = patch.auth().orElse(current.connectorDetails().auth());
        Knowledge.ConnectorDetails cd = new Knowledge.ConnectorDetails(
                current.connectorDetails().type(),
                current.connectorDetails().connectionId(), // connection binding is stable across edits
                auth);

        Knowledge.Config cur = current.config();
        Knowledge.ScheduleSettings schedule = new Knowledge.ScheduleSettings(
                patch.schedule().cron().orElse(cur.scheduleSettings().cron()),
                patch.schedule().interval().orElse(cur.scheduleSettings().interval()),
                patch.schedule().enabled().orElse(cur.scheduleSettings().enabled()));
        Knowledge.WebhookSettings webhook = new Knowledge.WebhookSettings(
                patch.webhook().enabled().orElse(cur.webhookSettings().enabled()),
                patch.webhook().secret().orElse(cur.webhookSettings().secret()));
        Knowledge.Backfill backfill = new Knowledge.Backfill(
                patch.backfillEnabled().orElse(cur.backfill().enabled()));
        Knowledge.Config config = new Knowledge.Config(schedule, webhook, backfill);

        return current.withEdits(patch.name().orElse(current.name()), cd,
                patch.inputs().orElse(current.inputs()), config, now);
    }

    /** True when the resolved cadence (custom cron/interval) changed and must be re-resolved. */
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

    /**
     * Config-class edit: a single in-place write, plus at most a small scheduling side-effect. None
     * of these change what is fetched or how an item is identified, so no source calls and no cursor
     * disruption.
     */
    private Knowledge applyConfigEdit(Knowledge current, Knowledge updated) {
        Knowledge toSave = updated;
        // A cadence change clears nextSyncDueAt (→ null = "due now") so ForwardCursorScheduler
        // re-resolves the cadence on its next tick instead of waiting out the old due time.
        if (cadenceChanged(current, updated)) {
            toSave = toSave.withNextSyncDueAt(null);
        }
        knowledge.save(toSave);

        // scheduleEnabled false→true: re-arm forward cursors so sync resumes promptly.
        if (!current.config().scheduleSettings().enabled()
                && updated.config().scheduleSettings().enabled()) {
            triggerSync(current.id());
        }
        return get(current.id()).orElse(toSave);
    }

    /**
     * Provisioning-class edit: pause → re-verify → re-discover → reconcile → restore status. Reuses
     * the existing add/reconcile machinery. The anchor stays fixed across the edit, so forward still
     * means {@code >= anchor} and backward {@code < anchor} — no gap or overlap is introduced.
     */
    private Knowledge reprovision(Knowledge current, Knowledge updated, boolean membershipChanged) {
        String id = current.id();
        KnowledgeStatus original = current.status();

        // 1. Pause: park claimable cursors and hold the knowledge non-ACTIVE so nothing is leased
        //    mid-change. Persist the edited config now (mirrors add: the record reflects the attempt;
        //    a verify failure below then lands it in ERROR without discarding what the user submitted).
        cursors.suspendByKnowledge(id);
        Knowledge held = updated.withStatus(KnowledgeStatus.PAUSED);
        knowledge.save(held);

        try {
            SourceConnector connector = connectors.get(held.connectorDetails().type());
            verifyConnectionIfRequired(connector, held);             // resolve + verify the connection
            connector.verify(held);                                  // bad inputs → throw → ERROR
            List<SourceIterable> iterables = discover(held, DiscoveryTrigger.RECONCILE);

            // 3.1 Iterable-level reconcile — but PARK, don't purge, on shrink (design §3.1).
            DirCounts created = createCursors(held, iterables);
            DirCounts revived = reviveReappearedIterables(held, iterables);
            DirCounts parked = parkDisappearedIterables(held, iterables);

            // 3.2 Within-iterable membership re-walk when the signature moved (design §3.2). Bump the
            //     generation and persist it BEFORE resetting cursors, so the async re-walk stamps
            //     freshly-seen entities at the new value and narrowed-out ones are left behind.
            Knowledge effective = held;
            if (membershipChanged) {
                effective = held.bumpGeneration().withStatus(KnowledgeStatus.PAUSED);
                knowledge.save(effective);
                rewalkForMembershipChange(effective, iterables);
            }

            recordDiscovery(effective, DiscoveryTrigger.RECONCILE, iterables.size(),
                    created, revived, parked);

            // 5. Restore status. A knowledge that was PAUSED stays parked — its freshly created/
            //    revived/re-walked cursors are parked too so the ingestion loop won't pick them up.
            //    Anything else ends ACTIVE with its cursors re-armed.
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
     * Park (retire) the cursors of iterables that {@code discover} no longer returns — but, unlike
     * the dynamic-discovery reconcile, WITHOUT purging their chunks/entities. On an edit the framework
     * can't tell an intentional narrowing from an accidental scope/account drop, so the data is kept
     * and stays searchable; the deferred Phase 2 purge removes it deliberately. Using {@code RETIRED}
     * (not {@code SUSPENDED}) keeps them out of the end-of-flow resume and lets the existing revive
     * path bring them back automatically if the iterable returns.
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
     * Re-walk every live iterable after a membership-signature change: rewind both its cursors to the
     * start so the async ingestion loop re-covers the full range under the new rule — backward
     * re-covers {@code < anchor}, forward re-covers {@code [anchor, now]}. Change-detection makes this
     * cheap: every unchanged, already-{@code INDEXED} item is skipped (no re-embed) and only its
     * generation mark is touched.
     *
     * <p>Backfill caveat (design §3.2): reaching historical adds below the anchor needs a backward
     * cursor. When backfill is off there is none, so this is a forward-only re-walk that still catches
     * adds in {@code [anchor, now]}; temporarily creating a backward cursor for the walk is deferred.
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

    /**
     * Overlay freshly-computed rollup counters onto a knowledge. Stats are derived and
     * reporting-only, so rather than maintain them on the (hot) ingestion/indexing write path — which
     * would mean recomputing on every entity — we compute them here, on the comparatively rare read.
     * The counts are three indexed {@code entities} queries; always accurate, never stale.
     */
    private Knowledge withFreshStats(Knowledge kn) {
        long total = entities.countByKnowledge(kn.id());
        long indexed = entities.countByKnowledgeAndStatus(kn.id(), EntityStatus.INDEXED);
        long failed = entities.countByKnowledgeAndStatus(kn.id(), EntityStatus.FAILED);
        return kn.withStats(new Knowledge.Stats(total, indexed, failed));
    }

    @Override
    public void pause(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.PAUSED);
        cursors.suspendByKnowledge(id); // park claimable cursors so they can't starve active knowledge
    }

    @Override
    public void resume(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.ACTIVE);
        cursors.resumeByKnowledge(id); // re-arm cursors parked while paused
    }

    @Override
    public void delete(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.DELETED); // stop scheduling immediately
        index.deleteByKnowledge(id);                          // remove derived chunks
        entities.deleteByKnowledge(id);                       // remove canonical entities
        cursors.deleteByKnowledge(id);                        // remove cursors
        discoveryStatus.deleteByKnowledge(id);                // remove discovery-status record(s)
        knowledge.delete(id);                                 // finally drop the knowledge record
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
        // One discover serves all three reconciliation steps below (records its outcome/failure).
        List<SourceIterable> iterables = discover(kn, DiscoveryTrigger.RECONCILE);

        DirCounts created = createCursors(kn, iterables);
        DirCounts revived = reviveReappearedIterables(kn, iterables);
        DirCounts retired = retireDeletedIterables(kn, iterables);

        recordDiscovery(kn, DiscoveryTrigger.RECONCILE, iterables.size(), created, revived, retired);

        if (created.total() > 0 || revived.total() > 0 || retired.total() > 0) {
            LOG.info("Reconcile for knowledge " + id + ": +" + created.total() + " new, "
                    + revived.total() + " revived, " + retired.total() + " retired cursor(s)");
        }
        return created.total();
    }

    /** Revive cursors retired for an iterable that has since reappeared, refreshing their attributes. */
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

    /**
     * Retire cursors whose iterable no longer exists at the source, and purge that iterable's
     * indexed data (chunks) and canonical entities first. Idempotent: already-retired or
     * currently-running cursors are skipped and caught on a later pass.
     */
    private DirCounts retireDeletedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Set<String> liveIds = new HashSet<>();
        iterables.forEach(it -> liveIds.add(it.iterableId()));
        List<Cursor> all = cursors.findByKnowledge(kn.id());

        // Distinct iterables that are gone but still have a retire-able (non-RETIRED, non-running) cursor.
        Set<String> goneIterables = new LinkedHashSet<>();
        for (Cursor c : all) {
            if (!liveIds.contains(c.iterableId())
                    && c.status() != CursorStatus.RETIRED && c.status() != CursorStatus.IN_PROGRESS) {
                goneIterables.add(c.iterableId());
            }
        }
        // Purge derived chunks then canonical entities (same order as the knowledge-delete cascade).
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

    /**
     * Run {@code connector.discover}, recording a {@code FAILED} discovery status for each of the
     * knowledge's active grabber directions before rethrowing — so a failed enumeration is always
     * inspectable from the {@code discovery} collection (not just the log). On success the caller
     * records the {@code OK} runs once it knows the per-direction cursor counts.
     */
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

    /** Record one discovery status per active grabber direction, attributing each its own counts. */
    private void recordDiscovery(Knowledge kn, DiscoveryTrigger trigger, int iterablesFound,
                                 DirCounts created, DirCounts revived, DirCounts retired) {
        for (CursorDirection direction : activeGrabberDirections(kn)) {
            discoveryStatus.record(DiscoveryStatus.Run.ok(kn.id(), direction, trigger, iterablesFound,
                    new DiscoveryStatus.Counts(created.of(direction), revived.of(direction),
                            retired.of(direction))));
        }
    }

    /**
     * The grabber directions this knowledge actually runs — and therefore the discovery records it
     * has: a forward grabber whenever the source supports it, and a backward grabber only when the
     * source supports it <em>and</em> backfill is enabled. Mirrors exactly which cursors
     * {@link #createCursors} makes, so a record exists per real grabber and no phantom one.
     */
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
     * Create cursors for every (iterable × supported direction) the source exposes. Idempotent:
     * deterministic cursor ids + {@code insertIfAbsent} mean re-running only adds cursors for
     * iterables that appeared since last time. Returns the newly-created cursors split by direction.
     */
    private DirCounts createCursors(Knowledge kn, List<SourceIterable> iterables) {
        var supported = connectors.get(kn.connectorDetails().type()).supportedDirections();
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        int backward = 0;
        int forward = 0;
        for (SourceIterable iterable : iterables) {
            // Backward (history) only if the source supports it AND backfill is enabled.
            if (backfill && supported.contains(CursorDirection.BACKWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.BACKWARD))) {
                backward++;
            }
            // Forward (incremental) whenever the source supports it.
            if (supported.contains(CursorDirection.FORWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.FORWARD))) {
                forward++;
            }
        }
        return new DirCounts(backward, forward);
    }

    /** Per-direction tallies for the grabber-scoped discovery records. */
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
                iterable.attributes(), // snapshot the grab() inputs so the runner needn't re-discover
                direction,
                CursorPosition.start(),
                CursorStatus.AVAILABLE,
                null,
                Cursor.Retry.zero(),
                Cursor.Stats.zero(),
                new Cursor.Scope(kn.connectorDetails().type()));
    }
}
