# Knowledge Lifecycle — end to end

This document follows a single **Knowledge** from the moment a user adds it through to its content
being searchable, including pause/resume/delete and the failure paths. It also draws a hard line
between what the **framework** does (generic, written once) and what a **source connector** must do
(the only code you write to add a new integration).

Read alongside [`indexing-design.md`](./indexing-design.md) (the spec) and
[`indexing-implementation.md`](./indexing-implementation.md) (the design→code map).

---

## 0. The three lifecycles at a glance

A Knowledge owns Cursors; Cursors produce Entities; Entities produce chunks in OpenSearch.

```
Knowledge:  DRAFT ──► ACTIVE ──► PAUSED ──► ACTIVE ──► DELETED
                         │
                         ├── creates Cursors (per iterable, per direction)
                         ▼
Cursor:     AVAILABLE ──► IN_PROGRESS ──► (EXHAUSTED | IDLE | AVAILABLE | FAILED)
                         (paused knowledge parks cursors at SUSPENDED; resume re-arms them)
                         │
                         ├── produces Entities (upsert by knowledgeId+externalId)
                         ▼
Entity:     INGESTED ──► INDEXING ──► INDEXED        (+ FAILED, DELETED)
                         │
                         ▼
            chunks written to OpenSearch  ──►  searchable
```

| Layer | States | Defined in |
|---|---|---|
| Knowledge | `DRAFT, ACTIVE, PAUSED, ERROR, DELETED` | `enums.KnowledgeStatus` |
| Cursor | `AVAILABLE, IN_PROGRESS, IDLE, SUSPENDED, EXHAUSTED, FAILED` | `enums.CursorStatus` |
| Entity | `INGESTED, INDEXING, INDEXED, FAILED, DELETED` | `enums.EntityStatus` |

---

## 1. How a user adds a Knowledge

### Request
```bash
curl -X POST localhost:8080/api/knowledge -H 'Content-Type: application/json' -d '{
  "name": "My Documents",
  "type": "LOCAL_FS",
  "auth": {},
  "inputs": { "rootPath": "/home/me/Documents" },
  "cron": null,
  "interval": null,
  "scheduleEnabled": true,
  "backfillEnabled": true
}'
```

- `type` selects the connector (`SourceType`).
- `auth` is an opaque credentials blob the connector understands (empty for LOCAL_FS).
- `inputs` is connector-specific (a root path here; a channel list or Gmail query for others).
- `cron` / `interval` / `scheduleEnabled` control forward (incremental) re-arming. Leave both `cron`
  and `interval` `null` to inherit the **connector default** (LOCAL_FS = 1 day) and then the
  **global default** (`app.scheduler.default-interval`, 1 day); set one to override (cron wins over
  interval). `scheduleEnabled: false` turns forward scheduling off entirely. See `ScheduleResolver`.
- `backfillEnabled` controls whether history is walked backward on activation.

### What happens synchronously (`DefaultKnowledgeService.add`)
1. **Build the draft.** A `Knowledge` is created with `id = kn_…`, **`anchor = now`** (the boundary
   between backward backfill and forward incremental), `status = DRAFT`, `stats = 0`.
2. **Verify** — `connector.verify(knowledge)`. The connector validates credentials/inputs and
   throws on failure (LOCAL_FS checks `rootPath` is a readable directory). A throw here surfaces as
   an error to the caller and the Knowledge is not activated.
3. **Persist** the draft to Mongo (`knowledge` collection).
4. **Discover iterables** — `connector.discover(knowledge)` returns the independently-paged
   sub-streams (folders, channels, labels…). LOCAL_FS returns a `root` iterable plus one per
   top-level sub-directory.
5. **Create cursors.** For each iterable, honoring `connector.supportedDirections()`:
   - a **backward** cursor *if* backfill is enabled **and** the source supports `BACKWARD`;
   - a **forward** cursor if the source supports `FORWARD`.

   Each cursor starts `AVAILABLE`, `position = CursorPosition.start()`, with a deterministic id
   (`Ids.cursorFor(knowledgeId, iterableId, direction)`) so re-running discovery never duplicates
   them.
6. **Activate** — `status = ACTIVE`. The Knowledge is returned to the caller.

> **Iterables that appear later.** Some sources grow their iterable set over time (a new child
> folder, a new Slack channel). A connector signals this with `hasDynamicIterables() = true`, and
> `IterableDiscoveryScheduler` periodically calls `KnowledgeService.reconcileCursors` to re-discover
> and create cursors for any new iterables — idempotently, thanks to the deterministic ids above, so
> existing cursors are untouched and only genuinely new sub-streams get backward+forward cursors. A
> vanished iterable's cursor self-terminates (`EXHAUSTED`) the next time the runner can't resolve it.

> From here on, everything is driven by background poll loops. The POST returns immediately; the
> actual fetching and indexing happen asynchronously.

```
POST /api/knowledge
  └─ KnowledgeResource.create
       └─ DefaultKnowledgeService.add
            1. build draft (anchor = now, DRAFT)
            2. connector.verify(...)        ◄── SOURCE SIDE
            3. knowledge.save(draft)
            4. connector.discover(...)       ◄── SOURCE SIDE
            5. create cursors (BACKWARD?/FORWARD per supportedDirections + backfill)
            6. status = ACTIVE
```

---

## 2. What happens afterwards — Stage 1: Ingestion (source → Mongo)

A poll loop (`IngestionJob.tick`, every `app.ingestion.poll-interval`, default 30s) turns cursors
into entities.

### Per tick
1. **Find claimable cursors** — `AVAILABLE`, or `IN_PROGRESS` whose lease has expired (crash
   recovery), ordered **least-recently-run first** (`stats.lastRunAt` ascending, never-run first) so
   no active knowledge can monopolise the bounded batch. Direction is irrelevant; backward and
   forward are treated identically.
2. For each candidate:
   - Skip if its Knowledge isn't `ACTIVE`. If it is `PAUSED`, **park** the knowledge's claimable
     cursors (`→ SUSPENDED`) so they drop out of the batch — a backstop for cursors that were leased
     when the knowledge was paused (the bulk park happens in `pause()`; `resume()` re-arms). Orphan
     cursors (knowledge gone) are left for the delete path.
   - **Acquire a permit** from `PermitService`, scoped at three levels at once — `global`,
     `connector:<TYPE>`, `knowledge:<id>` — so one source can't starve the rest. No permit → try
     again next tick.
   - **Atomically lease the cursor** (`claim`): only succeeds if still claimable; flips it to
     `IN_PROGRESS` with a TTL lease. Lost the race → move on.
   - Run the lease, then always release the permit.

### One lease (`IngestionRunner.runLease`) — the page loop
Resolve the `SourceIterable` for the cursor, then loop up to `batchesPerLease` pages:

```
position = cursor.position            (CursorPosition.start() on the very first run)
repeat up to batchesPerLease times:
    page = connector.grab(GrabRequest(knowledge, iterable, direction, position, maxItems))  ◄── SOURCE SIDE
    for each RawItem in page.items:    persist as an Entity (upsert by knowledgeId+externalId)
    position = page.nextPosition        (source-defined)
    owned = cursors.advancePosition(id, worker, position, …, newExpiry)
                                        ◄── ONE fenced write: persist position + bump stats + renew
                                            lease — but only if I still own the lease
    if not owned:  STOP (don't release)  ◄── lost the lease; the new owner continues
    permit heartbeat
    if not page.hasMore:  set resting status and stop
```

> **Why the lease is renewed each page — and *fenced*.** When a worker claims a cursor it gets a
> lease with a TTL (`app.ingestion.lease-seconds`, default 900s) — a "this cursor is mine until
> then" stamp. A single lease can process up to `batchesPerLease` pages (default 50), so after
> *each* page the worker **renews** the expiry (folded into `advancePosition`) — a heartbeat that
> says "still working, still mine" — preventing another worker from treating the cursor as abandoned.
> The same tick renews the `PermitService` permit. If the worker *crashes*, it stops renewing, the
> lease lapses, and the cursor becomes claimable again automatically — no manual cleanup.
>
> **The TTL must exceed the worst-case time for a *single* page.** The per-page renew protects a
> long *multi-page* lease, but it cannot protect one page that outlives the TTL (no renew fires
> until the page returns). If that happens the lease expires mid-page and another worker may
> re-claim the cursor. To keep that safe, the progress/release writes are **lease-fenced**:
> `advancePosition`, `release`, and `recordFailure` apply only if the caller still holds a live
> lease (an atomic compare-and-set on `lease.owner` + not-expired). A worker that has lost its lease
> gets `false` back and **stops touching the cursor** — its late writes are no-ops, so it can't
> clobber the new owner's position or status. The new owner simply continues from the persisted
> position; entity upserts are idempotent, so any overlap produces no duplicate entities.

**Persisting an item** (`persistItem`):
- **Tombstone** (`item.deleted == true`) → mark the existing entity `DELETED` (Stage 2 removes its
  chunks). 
- **Change detection** → if an entity with the same `(knowledgeId, externalId)` exists, its
  `checksum` matches, and it's already `INDEXED`, skip it (no rewrite). A *different* checksum means
  the item changed → it is re-upserted and re-indexed.
- **Otherwise** → upsert an `Entity` with `status = INGESTED`, content = `fileRef` (files) or inline
  `text`, preserving `id` and `createdAt` for updates. New/changed entities re-enter the indexing
  queue automatically.

> **What the `checksum` should encode (source side).** The framework treats `checksum` as an opaque
> change token — it re-indexes whenever it changes, full stop. So the connector must make it change
> whenever the item's content changes. Pick whatever the source makes cheap **and** reliable:
> - a **`(size, modifiedAt)`** pair, or a server-provided **etag / md5 / revision / version** — an
>   O(1) signal, no payload read. This is the cheap default and what most sources should use:
>   LOCAL_FS uses `(size, mtime)`; Google Drive an `md5Checksum`; S3 an ETag; Notion/Slack a
>   `last_edited_time`/`edited` stamp. **Make sure it changes when the item is modified** (include
>   the modified time or version) — that's the whole point.
> - a **content hash** of the bytes — most accurate (catches a byte change even if size and time are
>   identical), but it reads the entire payload every pass. Reach for it only when the source has no
>   reliable version/etag and you specifically need to catch size-and-time-preserving edits.
>
> Rule of thumb: prefer the source's native version/etag/`(size, mtime)`; fall back to a content
> hash only when no cheap change signal exists. The framework re-indexes whenever the checksum
> changes, so the only requirement is "a modified item must yield a different checksum".

**Resting status when the loop ends:**

| Condition | Resting status | Meaning |
|---|---|---|
| `hasMore = false`, backward cursor | `EXHAUSTED` | history fully drained (terminal) |
| `hasMore = false`, forward cursor | `IDLE` | caught up; waits for the scheduler to re-arm |
| Hit the batch cap, more pages remain | `AVAILABLE` | re-picked next tick to keep going |
| Exception, retries left | `AVAILABLE` (retry++) | retried next tick |
| Exception, past `retry-limit` | `FAILED` | dead-letter; needs intervention |

**At-least-once + idempotent:** the position is persisted *after* each page, and entity upserts are
keyed on `(knowledgeId, externalId)` — so a crash mid-lease resumes from the last completed page
with no lost or duplicated entities. The lease has a TTL, so a dead worker's cursor is reclaimed
automatically.

### Keeping incremental sync flowing (forward scheduling)
`ForwardCursorScheduler` flips a Knowledge's forward cursors `IDLE → AVAILABLE` — the one
forward-specific operation. It wakes on a fast tick (`app.scheduler.forward-interval`, default 1m)
but does **not** re-arm everything every tick: it arms only Knowledges whose `nextSyncDueAt` has
arrived, then rolls that due time forward by the Knowledge's **resolved schedule**. The schedule is
resolved per source by `ScheduleResolver` in three tiers — the Knowledge's own custom
`cron`/`interval`, else the connector's `defaultSchedule()` (e.g. LOCAL_FS = 1 day), else the global
default (`app.scheduler.default-interval` / `default-cron`); cron beats interval at the winning tier.
A `null` `nextSyncDueAt` (e.g. a freshly activated Knowledge) means "due now". The same flip is
triggered on demand by:
- `POST /api/index/knowledge/{id}/sync` (manual), and
- a webhook (future) calling `ForwardCursorScheduler.armNow(id)`.

Backward cursors never need re-arming: they re-arm themselves each lease until `EXHAUSTED`.

---

## 3. What happens afterwards — Stage 2: Indexing (Mongo → OpenSearch)

A second, independent poll loop (`IndexingJob.tick`, every `app.indexing.poll-interval`) turns
entities into searchable chunks. It first acquires a global indexing permit (bounds concurrency),
then:

> **Fairness across knowledges.** The job does not drain one knowledge's backlog before serving the
> rest. Each tick it finds the distinct knowledges with pending work
> (`distinctPendingKnowledgeIds`) and claims a small per-knowledge quota (`app.indexing.per-knowledge`)
> in **round-robin** order — rotating which knowledge leads each tick — up to a global per-tick
> budget (`app.indexing.batch`). So a knowledge with 100k pending entities and one with 5 both make
> progress every tick, without spawning a job per knowledge.

1. **Deletions** — `claimForDeletion` picks up tombstoned entities; `IndexingRunner.deleteEntityChunks`
   removes their chunks from OpenSearch (`deleteByEntity`) and marks cleanup complete.
2. **Indexing** — `claimForIndexing` atomically claims entities that are `INGESTED`, flagged
   `needsReindex`, or stuck `INDEXING` with an expired lease (honoring retry backoff), flipping each
   to `INDEXING`. For each (`IndexingRunner.indexEntity`):

```
extract text:
    file entity → read fileRef → ParserRegistry.get(contentType).parse(...)   (Tika / plain-text)
    text entity → use inline content.text
chunk:   ChunkingStrategy.chunk(entity, sourceType, text)
embed:   EmbeddingProvider.embedAll(chunkTexts)        (batched)
index:   SearchIndex.deleteByEntity(id) ; SearchIndex.indexChunks(embedded)   (idempotent replace)
mark:    EntityRepository.markIndexed(id, chunkCount, embeddingModel, now)  → status INDEXED
```

**Failure path:** retry with backoff (`status = INGESTED`, `retry.nextAttemptAt` set) until
`retry-limit`, then terminal `FAILED` with the captured error on the entity.

**Re-index without re-fetch:** because the entity retains `raw` + `fileRef`, re-indexing (new
chunking config or embedding model) is just `flagNeedsReindex` → the loop runs this path again. No
source calls. Chunks live **only** in OpenSearch and are always regenerable.

---

## 4. The content becomes searchable

Once an entity is `INDEXED`, its chunks are in the `chunks` alias and answer queries:

```
POST /api/search  →  DefaultSearchService
   → EmbeddingProvider.embed(query)          (unless purely lexical)
   → HybridRetriever  → SearchIndex.lexicalSearch (BM25) + vectorSearch (kNN) → RRF fuse
   → Reranker
   → (optional) SearchAgent → LlmProvider     grounded, cited answer
```

Every query filters on `knowledgeId`, so results are scoped to the sources the user chose.

---

## 5. Pause, resume, delete, and manual operations

| Action | Endpoint | Effect |
|---|---|---|
| Pause | `POST /api/knowledge/{id}/pause` | `status = PAUSED`; its claimable cursors are parked (`AVAILABLE/IDLE → SUSPENDED`) so they can't starve active knowledge in the claim batch. Leased cursors finish and are parked by the ingestion-loop backstop. See limitation [L1](./limitations.md#l1--pauseresume-park-vs-rearm-race). |
| Resume | `POST /api/knowledge/{id}/resume` | `status = ACTIVE`; parked cursors are re-armed (`SUSPENDED → AVAILABLE`) and get picked up again |
| Delete | `DELETE /api/knowledge/{id}` | `status = DELETED`, then tear down: `SearchIndex.deleteByKnowledge`, `EntityRepository.deleteByKnowledge`, `CursorRepository.deleteByKnowledge`, finally drop the Knowledge |
| Trigger sync | `POST /api/index/knowledge/{id}/sync` | Re-arm forward cursors now (`IDLE → AVAILABLE`) |
| Re-index one entity | `POST /api/index/entities/{id}/reindex` | `flagNeedsReindex` → Stage 2 re-runs (no re-fetch) |
| Delete one entity | `DELETE /api/index/entities/{id}` | `markDeleted` → Stage 2 removes its chunks |

---

## 6. Where the implementation is "source side"

Almost everything above is **framework** code, written once and reused by every source: the poll
loops, cursor leasing, permits, change detection, entity persistence, indexing, search. The **only**
code you write for a new integration is a `SourceConnector` (plus a `SourceType` enum constant).

### Framework vs. source side

| Concern | Who owns it |
|---|---|
| Poll loops, scheduling, leasing, permits, retries/backoff | **Framework** (`IngestionJob`, `IndexingJob`, `ForwardCursorScheduler`, repositories, `PermitService`) |
| Entity persistence, change detection, dedupe, idempotency | **Framework** (`IngestionRunner`, `EntityRepository`) |
| Chunking, embedding, OpenSearch indexing, search | **Framework** (`indexing.*`, `storage.search.*`, `retrieval.*`) |
| **Connecting to the source & authenticating** | **Source** — `verify` |
| **What the sub-streams are** | **Source** — `discover` → `SourceIterable`s |
| **How to paginate & what the cursor holds** | **Source** — `grab` + `CursorPosition` |
| **Mapping a source record → `RawItem`** | **Source** — inside `grab` |
| **Which directions are supported** | **Source** — `supportedDirections` |
| **Whether iterables grow over time** | **Source** — `hasDynamicIterables` (framework reconciles them) |
| **Per-source rate limiting / 429 backoff** | **Source** — inside `grab` (a connector-level concern) |
| **Detecting deletes (tombstones)** | **Source** — emit `RawItem.tombstone(externalId)` |

### The connector contract (what you implement)

```java
@ApplicationScoped
public class MySourceConnector implements SourceConnector {

    @Override public SourceType type() { return SourceType.MY_SOURCE; }

    // Optional: narrow this for forward-only / stream sources (default = both).
    @Override public Set<CursorDirection> supportedDirections() {
        return EnumSet.of(CursorDirection.FORWARD);
    }

    @Override public void verify(Knowledge knowledge) {
        // validate auth + inputs; throw on failure
    }

    @Override public List<SourceIterable> discover(Knowledge knowledge) {
        // enumerate sub-streams (channels/folders/labels), or a single iterable
        return List.of(new SourceIterable("all", "All items", Map.of()));
    }

    @Override public GrabPage grab(GrabRequest request) {
        CursorPosition pos = request.position();                  // resume point (start() first time)
        String token = pos.getString("pageToken");                // YOUR cursor shape

        // 1. call the source API for one page, honoring request.maxItems and the anchor boundary:
        //      forward  → items at/after knowledge.anchor()
        //      backward → items before knowledge.anchor()
        // 2. map each source record → RawItem (see below)
        // 3. return items + next position + whether more remain

        CursorPosition next = CursorPosition.builder().put("pageToken", nextToken).build();
        return new GrabPage(items, next, moreRemain);
    }
}
```

### Mapping a source record → `RawItem`

```java
// changeToken = the source's cheapest reliable change signal: (size, mtime), etag/md5, a version,
// or a content hash as a last resort. LOCAL_FS uses "size:<n>;mtime:<millis>".
RawItem.file(externalId, contentType, title, uri, changeToken, modifiedAt, fileRef, raw, metadata);
// or, for inline text items, the full constructor with `text` set and `fileRef = null`:
new RawItem(externalId, EntityType.MESSAGE, "text/plain", title, uri, checksum,
            modifiedAt, raw, text, /*fileRef*/ null, metadata, /*deleted*/ false);
// or a deletion:
RawItem.tombstone(externalId);
```

| `RawItem` field | What to put |
|---|---|
| `externalId` | **Stable natural key** in the source (path, message id, page id). The dedupe key. |
| `entityType` | `FILE`, `MESSAGE`, `EMAIL`, `PAGE`, … |
| `contentType` | MIME type — drives parser selection at indexing time |
| `title`, `uri` | display + citation locator |
| `checksum` | change-detection token: re-indexed whenever it changes. Use a content hash, or — when hashing isn't feasible — a `(size, modifiedAt)` / etag / version that **changes when the item is modified** |
| `modifiedAt` | source-side last-modified, if known |
| `raw` | the **complete** source payload — retained so re-index never re-fetches |
| `text` / `fileRef` | inline text for small text items, **or** a local file path for files (bytes stay on disk, never inlined) |
| `metadata` | normalized facets (`title`, `uri`, `author`, dates, labels…) used for display/filtering |
| `deleted` | `true` for a tombstone |

### Checklist for adding a new source

1. Add the constant to `enums.SourceType`.
2. Implement `SourceConnector` as an `@ApplicationScoped` bean (auto-discovered — no registry edit).
3. **Honor the anchor boundary**: forward returns items `>= anchor`, backward returns `< anchor`.
   This is what prevents gaps/duplicates between backfill and incremental.
4. **Own your pagination**: choose the `CursorPosition` fields you need (token, offset,
   timestamp+id, change-id…) and resume from `request.position()`. The framework just stores and
   replays it — never make `grab` rely on in-memory state between calls.
5. **Be idempotent / at-least-once safe**: a page may be re-fetched after a crash. Use stable
   `externalId`s and real `checksum`s so replays overwrite, not duplicate.
6. **Files**: pass a `fileRef` (local path), never the bytes — Mongo's 16 MB cap and memory both
   rule out inlining; the indexing stage reads the file directly.
7. **Deletes** (forward only): emit `RawItem.tombstone(externalId)` when the source reports an item
   gone; the indexing stage removes its chunks.
8. **Rate limiting**: enforce the source's request-rate limits and exponential backoff on 429/5xx
   *inside* `grab`. Permits cap *concurrency*; rate limiting is a connector concern.
9. **Errors**: throw from `grab` to signal a transient failure — the framework records the retry,
   backs off, and re-arms; past the retry limit the cursor goes `FAILED` (dead-letter).
10. **Thread-safety**: `grab` can be invoked concurrently for different cursors of the same
    connector; keep the bean stateless (all state lives in `CursorPosition`).
11. Add unit tests with the in-memory fakes in `src/test/java/io/personalassistant/testsupport`
    (see `IngestionRunnerTest` / `LocalFsConnectorTest`) — no Mongo/OpenSearch needed.

That's the whole surface. Implement those methods well and the source automatically gets backfill +
incremental sync, cursor leasing, crash recovery, scoped concurrency, change detection, chunking,
embeddings, hybrid search, and re-index-without-re-fetch — for free.
