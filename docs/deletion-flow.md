# Deletion across the flow

How data is removed today, path by path, and the gaps between those paths. This is the input to a
deliberate redesign of deletion, not a list to fix piecemeal: each gap below has an obvious local patch,
and adding those one at a time is how the flow ended up with six independent teardown orders. Items
marked *to verify* are inferred from code reading and not yet confirmed against a running system.

---

## 1. What each delete does today

### Knowledge — `DELETE /api/knowledge/{id}` → `DefaultKnowledgeService.delete`

Six writes in order, none transactional, none retried:

1. `knowledge.updateStatus(id, DELETED)` — schedulers and the ingestion loop stop picking it up.
2. `SearchIndex.deleteByKnowledge` — chunks.
3. `EntityRepository.deleteByKnowledge` — canonical entities.
4. `CursorRepository.deleteByKnowledge` — cursors.
5. `DiscoveryStatusRepository.deleteByKnowledge` — discovery records.
6. `KnowledgeRepository.delete` — the record itself.

Derived first, canonical last, so a crash part-way leaves the record present and `DELETED`, never
chunks without an owner. Nothing checks for digests that name the knowledge.

### Entity — `DELETE /api/index/entities/{id}` → `DefaultIndexingService.deleteEntity`

A tombstone, not a delete: `EntityRepository.markDeleted`. Stage 2 finishes it —
`IndexingJob.processDeletions` claims it under a lease, `IndexingRunner.deleteEntityChunks` calls
`SearchIndex.deleteByEntity`, and `markDeletionComplete` is lease-fenced. This is the only delete path
that is lease-aware end to end.

### Retention — `RetentionSweeper`

Tombstones through the same `markDeleted`, for entities past `expiresAt` or outside their knowledge's
retention window; chunk removal is left to Stage 2 as above. See `knowledge-lifecycle.md` §4b.

### Source-side removal — `RawItem.tombstone`

Handled by `IngestionRunner` (→ `DELETED`, then Stage 2), but no connector emits one. Limitation L2b.

### Iterable gone at the source — `DefaultKnowledgeService.retireDeletedIterables`

Runs during reconcile. For each iterable `discover` no longer returns: `SearchIndex.deleteByIterable`,
then `EntityRepository.deleteByKnowledgeAndIterable`, then `CursorRepository.retire` on its cursors
(`RETIRED`, kept so a reappearing iterable can be revived). Cursors that are `IN_PROGRESS` are skipped
and caught on a later pass.

### Edit that shrinks scope

Deliberately deletes nothing: retired iterables and narrowed-out entities are parked and marked
(`syncGeneration` / `lastSeenGeneration`), not purged. Limitation L2.

### Digest — `DefaultDigestService.delete`

`DigestRepository.delete` removes the digest and its runs. Deliveries it enqueued are not touched
(*to verify* whether a `PENDING` delivery for a deleted digest still sends).

### Channel — `DefaultChannelService.delete`

Refuses while any digest sends to it. Otherwise deletes its deliveries first, then the channel.

### Connection — `DefaultConnectionService.delete`

Refuses while any knowledge or channel is bound to it. Otherwise deletes it and, if it was the default
for its type, promotes the oldest remaining connection of that type.

### Task — `DefaultTaskService.delete`

Only user-written tasks can be deleted; refuses while any digest uses it.

---

## 2. Known gaps

### D1 — A delete during activation leaves orphan cursors (observed 2026-09-06)

**Scenario.** `add()` saves the `DRAFT`, then verifies, runs `discover`, `createCursors`,
`recordDiscovery`, and finally `updateStatus(ACTIVE)`. `discover` can take minutes — `JOB_BOARDS`
resolves every company in turn over HTTP with a 30s timeout each. A `delete` in that window finds no
cursors, entities or chunks yet, and removes the record. `add()` then carries on: `insertIfAbsent`
and the discovery record are upserts, so both are written for a knowledge that no longer exists, and
`updateStatus` matches no document without complaint.

**Left behind.** Cursors and a discovery record under a missing knowledge id. The incident: 71 forward
cursors (never run) and 1 discovery record for `kn_1cce49c8860b468faddec69ac90400cd`; no entities, no
chunks.

**Impact at the time.** High — it stopped all ingestion for a week. The 71 never-run cursors sorted to
the head of the claim batch (`stats.lastRunAt` ascending, limit 20); `IngestionJob.tryRun` skipped them
without writing, so the same 20 filled every tick. Fixed on the ingestion side on 2026-09-14: the
batch is now drawn only from eligible knowledges (`knowledge-lifecycle.md` § Per tick). The orphans
themselves are still created by this race and still never removed.

**Candidate directions.** A compare-and-set `DRAFT → ACTIVE` that, on failure, removes what activation
wrote; or refusing to delete a `DRAFT` mid-activation; or making activation and deletion one owned saga
(§3). *To verify*: whether `reprovision` (edit of auth/inputs) has the same window.

### D2 — A failed knowledge delete is never finished

**Scenario.** Any step of the cascade throws — most plausibly `SearchIndex.deleteByKnowledge` with
OpenSearch down.

**Left behind.** A `DELETED` knowledge still holding whatever the remaining steps would have removed.
Nothing retries it, and the API offers no way to re-run the delete short of calling it again by hand.

**Impact.** Low while single-user; storage and search pollution rather than corruption. Its cursors
are no longer a starvation risk (`DELETED` is not eligible for the claim batch).

### D3 — Knowledge delete ignores in-flight leases

**Scenario.** The cascade does not wait for, or fence out, workers holding leases on that knowledge.

- Ingestion: `IngestionRunner` upserts a page's entities *before* the lease-fenced
  `advancePosition`. A worker mid-page when steps 3–4 run writes that page's entities afterwards, then
  `advancePosition` fails (the cursor is gone) and it stops.
- Indexing: `IndexingRunner` writes an entity's chunks (`deleteByEntity` + `indexChunks`) *before* the
  lease-fenced `markIndexed`. An entity mid-indexing when step 2 runs gets its chunks written
  afterwards.

**Left behind.** Up to one page of entities, and the chunks of entities being indexed, under a missing
knowledge id. *To verify*: whether `IndexingJob` will go on to claim and index those orphan entities,
and whether an unscoped search returns their chunks.

**Impact.** Low frequency (needs a delete during active work), but the leftovers are searchable
content the user believes is gone.

### D4 — Deleting a knowledge doesn't check digests

**Scenario.** A digest's `knowledgeIds` names the deleted knowledge. Channel, connection and task
deletes all refuse while referenced; knowledge delete does not.

**Effect.** `OpenSearchSearchIndex` applies `knowledgeIds` as a filter whenever it is non-empty. A
digest scoped only to the deleted knowledge matches nothing and records an empty run every time —
indistinguishable from "nothing new". A digest scoped to several silently narrows.

### D5 — Cursors stranded under an `ERROR` knowledge

**Scenario.** Activation (`add`) or re-provisioning fails after cursors were created, and
`markError` parks the knowledge.

**Left behind.** Claimable cursors under an `ERROR` knowledge. Since 2026-09-14 they are excluded from
the claim batch, so they no longer stall anything, but nothing removes or re-arms them either.
*To verify*: what a retry of that knowledge does with the existing cursors.

### D6 — Orphan records are never swept

No job looks for cursors, discovery records, entities or chunks whose knowledge is missing. After the
D1 incident one orphan discovery record (`dsc_kn_1cce49c8860b468faddec69ac90400cd:FORWARD`) is still in
Mongo; it is harmless but will stay forever.

---

## 3. For the redesign

Starting points to discuss, not decisions:

- **One owner for teardown.** Six orders exist today (knowledge cascade, iterable retire, entity
  tombstone, retention, channel, digest). One component that knows every collection a knowledge owns
  makes a new collection a one-place change.
- **Resumable and idempotent.** Record the intent (`DELETED`, plus how far teardown got) and let a
  sweeper finish it, so D2 heals itself and a crash never needs manual cleanup.
- **Lease-aware.** Either wait for live leases to lapse before removing entities and chunks, or fence
  the write paths on knowledge status as well as the lease (D3).
- **Activation and deletion must not interleave** (D1) — the same owner, or a status CAS on both.
- **One rule for references.** Refuse while referenced (as channel/connection/task do) or detach with
  a visible record — but the same rule for knowledge → digest (D4).
- **An orphan sweep** as the safety net behind all of the above (D6).
