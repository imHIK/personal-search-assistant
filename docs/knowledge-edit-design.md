# Editing a Knowledge — design

How an existing **Knowledge** is *edited* after it has been added: changing its name/schedule, its
credentials, or what it indexes — across **any** lifecycle status, without losing or silently
corrupting already-indexed data.

Read alongside [`knowledge-lifecycle.md`](./knowledge-lifecycle.md) (the add → ingest → index flow
this builds on) and [`limitations.md`](./limitations.md) (where the deferred parts are tracked).

> **Status of this document.** **Phase 1 is implemented** — the `update` routing, config/provisioning
> edits, park-don't-purge on shrink, the membership-signature re-walk, the generation marks, and the
> `PATCH` endpoint (see the [implementation map](#71-phase-1-implementation-map)). **Phase 2** (the
> unified deletion/purge path) remains deferred and is tracked as
> [L2 in `limitations.md`](./limitations.md#l2--edit-cleanup-is-deferred-no-purge-on-shrink). The
> split is called out in [§7](#7-phasing).

---

## 1. The core idea: one entry point, routed by what changed

There is a single use-case port:

```java
Knowledge update(String id, KnowledgePatch patch);
```

`update` does **not** behave like one operation. It loads the stored Knowledge, **diffs** the patch
against it, and routes by *what actually changed*, because the two kinds of edit have completely
different blast radius:

| Class | Fields | What it costs |
|---|---|---|
| **Config-class** | `name`, `config.scheduleSettings`, `config.webhookSettings`, `config.backfill` (true→false) | A single in-place write. No source calls, no cursor/entity/index disruption. |
| **Provisioning-class** | `connectorDetails.auth`, `inputs`, `config.backfill` (false→true) | Re-verify + re-discover + reconcile. Can change *what* is fetched and *which* items belong, so derived state may need to move. |

Two hard rules independent of class:

- **`connectorDetails.type` is immutable.** A different connector is effectively a different
  knowledge; changing it is rejected. Use delete + recreate.
- **`DELETED` knowledge cannot be edited.** Rejected.

Everything else is the detail of those two routes.

---

## 2. Config-class edits (in place)

None of these change what is fetched or how an item is identified, so they are applied with one
`KnowledgeRepository` write plus, at most, a small scheduling side-effect:

| Field changed | Action |
|---|---|
| `name`, `webhookSettings` | Write the field. Nothing else. |
| `scheduleSettings.cron` / `.interval` | Write the field **and** clear `nextSyncDueAt` (→ `null` = "due now") so `ForwardCursorScheduler` re-resolves the cadence on its next tick instead of waiting out the old due time. |
| `scheduleSettings.enabled` false→true | Write the field and `triggerSync` (re-arm forward cursors) so sync resumes promptly. |
| `scheduleSettings.enabled` true→false | Write the field. The scheduler simply stops arming; in-flight cursors finish naturally. |
| `backfill.enabled` true→false | Write the field. Stops *future* backward work; already-fetched history is left untouched. |

> `backfill.enabled` **false→true** is *not* in this table — turning backfill on means "go walk
> history now", which requires creating backward cursors for the existing iterables. That is a
> provisioning action and is handled by [§3](#3-provisioning-class-edits-re-provision).

---

## 3. Provisioning-class edits (re-provision)

`auth` and `inputs` changes (and `backfill` false→true) run a re-provision pipeline that **reuses the
existing add/reconcile machinery** rather than inventing a parallel one:

```
update(provisioning)
  1. pause            → status held non-ACTIVE; claimable cursors parked so none is leased mid-change
  2. connector.verify(updated)        ◄── SOURCE SIDE — throws on bad creds/inputs → ERROR, nothing else mutated
  3. connector.discover(updated)      ◄── SOURCE SIDE — the (possibly new) iterable set
  4. reconcile                         (see §3.1 and §3.2)
  5. restore status   → ACTIVE (or stay PAUSED if it was paused; ERROR on a verify/discover throw)
```

The `anchor` (the backward/forward boundary) **stays fixed** across an edit, so forward still means
`>= anchor` and backward `< anchor` — no gap or overlap is introduced.

### Why auth goes through the full path

A rotated credential is **not** assumed to preserve scope. New creds may belong to a different
account or carry narrower OAuth scopes, which silently changes the readable iterable set: cursors for
now-unreadable iterables would start failing inside `grab`, and newly-granted iterables would be
missed. So an `auth` change re-verifies *and* re-discovers, exactly like an `inputs` change. The
connector's `verify` is expected to validate required scopes/identity and throw when they are
insufficient — so a bad rotation lands the knowledge in `ERROR` with the reason captured, touching no
data.

### 3.1 Iterable-level reconcile (which folders/channels/labels exist)

This is the diff the framework already knows how to do (`reconcileCursors`): compare the discovered
iterable set against the existing cursors.

| Situation | Action |
|---|---|
| New iterable appeared | Create its cursors (backward if backfill on + supported, forward if supported). |
| Previously-retired iterable reappeared | Revive its cursors, refreshing attributes. |
| Iterable no longer discovered | **Park, do not purge** (see box). |

> **Park-don't-purge on shrink.** When an iterable disappears from `discover()`, the framework cannot
> tell an *intentional* removal (user narrowed `inputs`) from an *accidental* one (wrong account,
> transient partial consent). Deleting on that ambiguity is unsafe, so on shrink we **retire the
> cursor but skip the chunk/entity purge** — the data is kept and stays searchable for now. Mechanically
> this is `cursors.retire(...)` **without** the `index.deleteByIterable` / `entities.deleteByKnowledgeAndIterable`
> calls. Using `RETIRED` (not `SUSPENDED`) matters: a `SUSPENDED` cursor would be re-armed by the
> `resumeByKnowledge` at the end of the flow, whereas `RETIRED` stays parked and is automatically
> brought back by the existing `revive` path if the iterable returns (e.g. the user fixes the creds).
> Actual removal of the kept data is deferred to the Phase 2 purge ([§7](#7-phasing)).

### 3.2 Within-iterable membership (which items inside one iterable belong)

This is the subtle case that iterable-level diffing **cannot** catch. A filter change moves the
membership boundary *inside* a single iterable — e.g. a folder cursor whose `inputs` go from
`pdf + docx` to `pdf only`. The cursor's id and iterable are unchanged; only the rule for which items
belong has changed. The current machinery misses both directions:

- **Widened filter** (e.g. add `txt`): the newly-matching files are historical — below the anchor
  (backward already `EXHAUSTED`) or behind the forward cursor's current position — so neither cursor
  will ever re-emit them. There is no record to diff against, so they are silently never ingested.
- **Narrowed filter** (e.g. drop `docx`): those entities are already indexed; the connector simply
  stops emitting them and never tombstones them (tombstones are for source-side *deletes*, not
  filtered-out items). They linger as stale, searchable chunks.

Neither is solvable by a smarter diff. The fix is a **re-walk** plus a **mark**.

**Detecting that a re-walk is needed — `membershipSignature`.** `inputs` is an opaque,
connector-specific map; the framework cannot know which keys affect membership (`fileTypes`,
`query`, `globs`) versus which are cosmetic (a display label). So the connector declares it:

```java
/**
 * A stable hash of ONLY the input dimensions that change which items belong to an iterable.
 * The framework compares the signature before vs. after an edit and re-walks the iterables whose
 * signature changed. Default implementation may hash the whole inputs map (always-correct, just
 * coarser). Cosmetic inputs (display names) must be excluded so they don't trigger needless re-walks.
 */
String membershipSignature(Map<String, Object> inputs);
```

**Re-walk (catches adds).** For each iterable whose signature changed, reset **both** its cursors to
`CursorPosition.start()` and re-arm the backward one. Backward re-covers `< anchor`, forward
re-covers `[anchor, now]` — together the full history under the new rule. This is cheaper than it
sounds: change-detection skips every unchanged already-`INDEXED` file (no re-embed), so the cost is
source enumeration plus indexing only the genuinely-new matches.

> **Backfill caveat.** A membership re-walk needs a backward cursor to reach historical adds below
> the anchor. If `backfill` is off there is no backward cursor, so those adds are unreachable without
> temporarily creating one for the re-walk. (Forward-only re-walk still catches adds in `[anchor, now]`.)

**Mark (records the removes, defers the deletion).** To later remove the narrowed-out items without
re-walking again, we stamp generations now:

- Add `syncGeneration` (a counter) to `Knowledge`; **bump it** on every membership-affecting edit.
- Add `lastSeenGeneration` to `Entity`; **stamp it** with the knowledge's current `syncGeneration`
  every time a walk sees the item.

> **The skip path must still touch the mark.** Change-detection currently skips an unchanged
> `INDEXED` entity with *no write at all*. For the mark to be reliable, a skipped-because-unchanged
> entity must still have its `lastSeenGeneration` touched during a re-walk — otherwise a perfectly
> valid file would later look stale. So the skip path needs a cheap single-field update.

No deletion happens in Phase 1. After a *completed* re-walk for generation `G`, any entity still
stamped `< G` is, by construction, an item that no longer matches — material the Phase 2 sweep
removes. Recording it now means the deletion step, when built, needs no extra re-walk to reconstruct
what is stale.

---

## 4. Behaviour across every lifecycle status

The design is required to work on **any** `KnowledgeStatus`. It does, because routing + the
pause/reconcile/resume shape compose cleanly with each state:

| Status at edit time | Behaviour |
|---|---|
| `DRAFT` | Transient (add is synchronous). Treat like activation — apply, then run the relevant route. |
| `ACTIVE` | The main case. Provisioning edits pause → reconcile → resume; in-flight leases are contained by the existing lease-fencing + idempotent upserts. |
| `PAUSED` | Apply the edit but **stay parked** — do not auto-resume. New cursors are created parked so the ingestion loop won't pick them up. |
| `ERROR` | Editing **is** the recovery path: fix bad creds/inputs, `verify` re-runs; success → `ACTIVE`, failure → stays `ERROR` with the new reason. |
| `DELETED` | Rejected. |

---

## 5. Framework vs. source side

Consistent with the lifecycle doc: almost all of this is **framework** code written once. The only
new thing a **connector** must provide is the membership signature.

| Concern | Who owns it |
|---|---|
| `update` routing, diff, config writes, scheduling side-effects | **Framework** |
| pause/reconcile/resume, park-don't-purge, generation bump + entity stamping | **Framework** (reuses `reconcileCursors`, `pause`/`resume`, cursor `retire`/`revive`) |
| Re-verifying creds / re-discovering iterables | **Source** — existing `verify` / `discover` |
| **Which input dimensions affect membership** | **Source** — new `membershipSignature(inputs)` |

---

## 6. API surface

A partial update on the existing resource:

```
PATCH /api/knowledge/{id}
Content-Type: application/json
{ "name": "...", "inputs": { ... }, "auth": { ... }, "cron": "...", "scheduleEnabled": true, ... }
```

- Only present fields are treated as changes (patch semantics); absent fields are untouched.
- `type` present and different → `400`. `DELETED` → `409`/`404`.
- Returns the updated `Knowledge` (in `ERROR` with `lastError` set if re-verify/discover failed,
  mirroring `add`).

The DTO (`KnowledgePatchDto`) maps to a `KnowledgePatch` carrying only the provided fields, so the
service can diff present-vs-changed precisely. `KnowledgePatch` models this with `Optional<…>` per
field (empty = "not provided", present = "set to this"), and the service routes on it.

> **Wire caveat (Phase 1).** At the JSON boundary an *omitted* field and an explicit `null` both
> deserialize to a `null` Java field, so `KnowledgePatchDto` treats `null` as "not provided /
> unchanged" (the same boxed-nullable convention `KnowledgeDto` already uses for
> `scheduleEnabled`/`backfillEnabled`). One consequence: `cron`/`interval` cannot be *cleared back to
> inherit* through the patch API (a `null` there means "leave it"), only overwritten. The service-layer
> `KnowledgePatch` can express the clear precisely; exposing it would need a typed-null wire format
> (e.g. JSON-nullable), which is not built yet.

---

## 7. Phasing

**Phase 1 — implemented (no deletion):**
- `update(id, patch)` with config/provisioning routing; `type` and `DELETED` guards.
- Config-class in-place writes + scheduling side-effects.
- Provisioning-class pause → verify → discover → reconcile → restore status.
- Iterable-level reconcile with **park-don't-purge** on shrink.
- `membershipSignature` connector hook + signature-driven re-walk (reset cursors, re-arm backward).
- `syncGeneration` on `Knowledge` + `lastSeenGeneration` on `Entity`, stamped on every walk
  including the skip-unchanged path. **No deletes.**

**Phase 2 — deferred (one unified, confirmed purge):**
- Completion-gated **sweep** of entities with `lastSeenGeneration < syncGeneration`.
- **Purge** of parked (`RETIRED`) iterables' kept data.
- A single explicit/confirmed deletion path covering both, so destructive cleanup is deliberate.

Tracked as a deferred limitation in [`limitations.md`](./limitations.md) ([L2](./limitations.md#l2--edit-cleanup-is-deferred-no-purge-on-shrink)).

### 7.1 Phase 1 implementation map

Where each Phase 1 piece lives (all **framework**; the only source-side addition is the connector hook):

| Piece | Code |
|---|---|
| `update` routing + config/provisioning split, `type`/`DELETED` guards | `app/DefaultKnowledgeService#update` |
| Config-class in-place write + scheduling side-effects (clear `nextSyncDueAt`, re-arm on enable) | `DefaultKnowledgeService#applyConfigEdit` |
| Provisioning pause → verify → discover → reconcile → restore | `DefaultKnowledgeService#reprovision` |
| Park-don't-purge on shrink | `DefaultKnowledgeService#parkDisappearedIterables` (retire, no `deleteByIterable`) |
| Membership re-walk (rewind cursors) | `DefaultKnowledgeService#rewalkForMembership` + `CursorRepository#resetToStart` |
| `membershipSignature` connector hook | `ingestion.connector.SourceConnector#membershipSignature` (default hashes all inputs) |
| `syncGeneration` on Knowledge, bumped on membership edits | `domain.model.Knowledge#syncGeneration` / `#bumpGeneration` |
| `lastSeenGeneration` on Entity, stamped on every walk incl. the skip path | `domain.model.Entity#lastSeenGeneration`, `EntityRepository#stampLastSeen`, `IngestionRunner#persistItem` |
| Patch model + wire DTO + endpoint | `domain.service.KnowledgePatch`, `api.dto.KnowledgePatchDto`, `api.resource.KnowledgeResource#update` (+ `api.resource.PATCH`) |

Tests: `app/DefaultKnowledgeServiceEditTest` (covers every case in [§8](#8-test-plan-phase-1)).

---

## 8. Test plan (Phase 1)

Using the in-memory fakes in `src/test/java/io/personalassistant/testsupport` (no Mongo/OpenSearch),
mirroring `DefaultKnowledgeServiceTest`:

1. **Config-only edit** — `name`/`scheduleSettings` change does a single write; no `discover`/`verify`
   call; `nextSyncDueAt` cleared on cadence change; `triggerSync` on enable.
2. **Provisioning edit (inputs)** — re-verify + re-discover run; new iterables get cursors; missing
   iterables are **parked (`RETIRED`), data kept** (assert no purge call).
3. **Provisioning edit (auth)** — same path as inputs (verify + discover invoked).
4. **Type change rejected** — `400`, nothing mutated.
5. **`DELETED` rejected** — nothing mutated.
6. **`membershipSignature` triggers re-walk only on changed iterables** — changed iterable's cursors
   reset to `start()` + backward re-armed; unchanged iterables untouched.
7. **Adds re-ingested** — after a widening edit, the re-walk emits previously-missed items (via
   `StubConnector` returning the now-matching items).
8. **Generation stamping** — `syncGeneration` bumps on a membership edit; `lastSeenGeneration` is
   stamped on changed **and** unchanged (skip-path) entities.
9. **Status matrix** — `ERROR`→recovery, `PAUSED`→stays parked, `ACTIVE`→ends `ACTIVE`.
