# Indexing Subsystem — Implementation Instructions

Status: **draft for review**. This captures the agreed design for how a user's knowledge
is ingested from a source and indexed into OpenSearch. It folds in the review feedback and
aligns vocabulary with the existing codebase. Open decisions are marked **[DECIDE]**.

---

## 1. Principles (hard rules)

1. **Two decoupled stages.** *Ingestion* (source → Mongo) and *Indexing* (Mongo →
   OpenSearch) are independent, each driven by its own cursors and job runner. Neither
   blocks the other.
2. **Mongo is the source of truth; OpenSearch is rebuildable.** We persist the **raw
   source payload** on each entity and transform it into text + metadata at *indexing*
   time. Re-indexing (new chunking config, new embedding model) must never require
   re-fetching from the source.
3. **At-least-once, idempotent.** Always *persist entities, then advance the cursor*.
   Upserts are keyed so a replay overwrites rather than duplicates.
4. **Everything is resumable.** Cursors, leases, and per-entity status make any stage
   safe to crash and restart.

---

## 2. Vocabulary & mapping to existing code

| Concept (this doc) | Meaning | Existing code it maps to |
|---|---|---|
| **Knowledge** | A user-added source configuration (connector + inputs + config + status) | supersedes `domain.model.Source` |
| **Iterable** | A sub-stream within a knowledge that is paged independently (a Slack channel, a Drive folder, a Gmail label) | new |
| **Grabber** | Pulls data from a source in a direction (backward/forward) | extends `ingestion.connector.SourceConnector` |
| **Cursor** | Persisted position + lease for one (knowledge, iterable, direction) | new (`Source.SyncState` becomes per-cursor) |
| **Entity** | Canonical ingested record: raw payload + extracted content + metadata + sharings + status | supersedes `domain.model.Document` |
| **Chunk** | Indexable unit derived from an entity at indexing time | existing `domain.model.Chunk` |
| **PermitService** | Reusable concurrency limiter with leases | new (`common`) |

> The "existing code" column records what this design replaced. The rename has since landed —
> `Source` and `Document` no longer exist anywhere in the codebase, and `Knowledge`, `Iterable`,
> `Cursor`, and `Entity` are the live vocabulary.

---

## 3. The Knowledge object

```jsonc
{
  "id": "kn_...",
  "connectorDetails": { "type": "LOCAL_FS|GMAIL|SLACK|...", "auth": { /* opaque */ } },
  "inputs": { /* what to index: paths, folder ids, channel ids, query… */ },
  "config": {
    "scheduleSettings": { "cron": "0 */15 * * * ?", "enabled": true },
    "webhookSettings":  { "enabled": false, "secret": "…" },
    "backfill":         { "enabled": true }
  },
  "anchor": "2026-06-22T00:00:00Z",   // boundary between backward and forward (see §5)
  "status": "DRAFT|ACTIVE|PAUSED|ERROR|DELETED",
  "stats": { "entities": 0, "indexed": 0, "failed": 0 },
  "createdAt": "...", "updatedAt": "..."
}
```

### Lifecycle when a knowledge is added
1. Validate `connectorDetails` (connector `verify`).
2. Set `anchor = now`.
3. **Discovery**: the connector enumerates its iterables. For each iterable, create the
   cursors below. Then set status `ACTIVE`.

---

## 4. Cursor — first-class entity

A cursor is **position + lease + status**, not just a position. One cursor per
`(knowledgeId, iterableId, direction)`.

```jsonc
{
  "id": "cur_...",
  "knowledgeId": "kn_...",
  "iterableId": "channel_C123",        // identifies the sub-stream
  "direction": "BACKWARD | FORWARD",
  "position": "<opaque source token>", // page token / timestamp / change id
  "status": "AVAILABLE | IN_PROGRESS | IDLE | SUSPENDED | EXHAUSTED | FAILED",
  "lease":  { "owner": "worker-7", "expiresAt": "..." },  // null when free
  "retry":  { "count": 0 },
  "stats":  { "lastRunAt": "...", "fetched": 0 },
  "scope":  { "connectorType": "SLACK" }   // used by PermitService scoping
}
```

**The job only ever asks one question: is this cursor `AVAILABLE`?** So there are really
just two operational states:

- **`AVAILABLE`** — re-pick me. Set when the cursor is created, when more pages remain
  (continue), or when the forward scheduler re-arms it.
- **`IN_PROGRESS`** — leased and running right now.

Everything else simply means "don't pick me" — resting/terminal bookkeeping states:
- **`IDLE`** — a forward cursor that has caught up; sits here until its schedule flips it
  back to `AVAILABLE`.
- **`EXHAUSTED`** — a backward cursor that has drained all history (terminal).
- **`FAILED`** — errored past the retry limit; needs intervention (dead-letter). The limit counts
  *consecutive* failures — a successful run resets the streak — and nothing auto-reclaims a `FAILED`
  cursor. `POST /api/index/knowledge/{id}/retry-failed` is the only way out.

> **Backward and forward share the exact same job logic.** The only difference is what makes
> a cursor `AVAILABLE` again: a backward cursor re-arms *itself* until `EXHAUSTED`, while a
> forward cursor is re-armed by the **scheduler**. The job itself never branches on direction.

---

## 5. Stage 1 — Ingestion

### Grabbers
The connector exposes a directional fetch. Two grabber modes to start:

- **Backward grabber** — walks history from `anchor` backward until the source has no more
  data, then the cursor goes `EXHAUSTED`. Used once per iterable for backfill.
- **Forward grabber** — fetches everything from `anchor` forward; re-runs on
  schedule/webhook to pick up new + changed + deleted items. Lives forever.

> **Boundary rule:** forward handles `≥ anchor`, backward handles `< anchor`. This is what
> prevents gaps and duplicates between backfill and incremental.

### Ingestion job (the loop)

**One batch = one grabber page; one lease = up to N batches (default ~50).** When a cursor
is leased we keep paging within that same lease — up to `batchesPerLease` pages — instead of
releasing after every page. This cuts the per-page overhead of re-querying, re-permitting
and re-leasing, and lets each pick make real progress. We still persist `position` after
*each* batch, so a crash mid-lease resumes from the last completed page (no lost or
duplicated work).

Runs continuously / on a tick:
1. Find cursors with `status = AVAILABLE` and no live lease. (Direction is irrelevant —
   backward and forward are treated identically here.)
2. For each, ask **PermitService** for a permit (scoped — see §7). If none free, skip and
   try next tick.
3. **Lease** the cursor (atomic find-and-modify: `status = IN_PROGRESS`, set `lease.owner`,
   `lease.expiresAt`).
4. **Page loop** — repeat up to `batchesPerLease` times, or until `hasMore = false`, or
   until the lease is near expiry:
   a. Run the grabber for one batch from `position` → items + `nextPosition` + `hasMore`.
   b. **Persist** items as Entities (upsert by `knowledgeId + externalId`); capture raw
      content + metadata. (Sharings/ACLs out of scope — single-user; see §11.)
   c. After persistence succeeds, **advance** `position = nextPosition` (persisted each batch).
   d. **Renew the lease** (heartbeat) so a long run doesn't lose it.
5. Set the cursor's resting status, then release the permit + lease:
   - stopped at the batch cap, more pages remain → **`AVAILABLE`** (re-picked next tick to
     keep going).
   - `hasMore = false`, backward → **`EXHAUSTED`** (history drained; terminal).
   - `hasMore = false`, forward → **`IDLE`** (caught up; the scheduler will re-arm it).
6. On error: persist progress so far, increment `retry.count`, release the lease, and set
   **`AVAILABLE`** to retry (optionally after a short backoff); past the retry limit → set
   **`FAILED`** (dead-letter; needs intervention).

### Forward scheduling
A scheduled task (from each knowledge's `scheduleSettings`) does one tiny thing: flip that
knowledge's forward cursors from `IDLE` → `AVAILABLE`. The normal loop above then re-picks
them like any other available cursor. That is the **only** forward-specific logic — webhooks
trigger the same flip on demand.

### Handling updates & deletes (forward only)
- **Update**: re-upsert the entity; mark it `needsReindex = true`.
- **Delete (tombstone)**: mark entity `DELETED`, and the indexing stage removes its chunks
  from OpenSearch.

### Rate limiting
Permits cap *concurrency*. Separately, each connector enforces **per-source request-rate
limits** and **exponential backoff on 429/5xx**. Keep these as connector-level concerns.

---

## 6. The Entity (Mongo `entities` collection)

```jsonc
{
  "id": "ent_...",
  "knowledgeId": "kn_...",
  "iterableId": "channel_C123",
  "entityType": "FILE | MESSAGE | EMAIL | PAGE | ...",
  "externalId": "<natural key in source>",
  "raw": { /* complete source response — the controllable re-index source */ },
  "content": {                       // populated at ingest OR deferred to indexing
    "text": null,                    // for text entities; files extracted at indexing
    "fileRef": "file:///abs/path/to/file"  // local filesystem path; bytes stay on disk
  },
  "metadata": { "title": "...", "author": "...", "createdAt": "...", "uri": "..." },
  "checksum": "sha256:…",
  "status": "INGESTED | INDEXING | INDEXED | FAILED | DELETED",
  "needsReindex": false,
  "index": { "chunkCount": 0, "embeddingModel": null, "indexedAt": null, "error": null },
  "retry": { "count": 0, "nextAttemptAt": null },
  "createdAt": "...", "updatedAt": "..."
}
```

Indexes: `{ knowledgeId, externalId }` unique; `{ status }`; `{ needsReindex }`;
`{ "retry.nextAttemptAt" }`.

> **File bytes never go in the document.** Files stay on the **local filesystem**; the
> entity keeps a `fileRef` (absolute path) and the indexing stage reads the file directly
> from that path. (Mongo's 16 MB cap rules out inlining anyway; GridFS / object storage
> can be added later if files ever need to live off-box.)

---

## 7. PermitService (reusable concurrency limiter)

A standalone, reusable service. Limits how many units of work run at once, scoped at
multiple levels so one source can't starve the rest.

```java
interface PermitService {
    Optional<Permit> tryAcquire(String scopeKey, int max);  // null if at capacity
    void renew(Permit permit);                              // heartbeat the lease
    void release(Permit permit);
}
```

- **Scopes** (compose them): `global`, `connector:SLACK`, `knowledge:kn_123`.
- **Leased with TTL**: every permit has an expiry, so a crashed worker's permit is reclaimed
  automatically when it lapses. (The *cursor's* own lease lives on its Mongo document, §4 —
  same idea, separate store.)
- **Backing store: Redis.** Permits are fast, ephemeral, key-value state with TTLs — a
  natural fit: `SET key val NX PX <ttl>` to acquire, key expiry to auto-reclaim a dead
  worker's permit, and scoped counters/keys per scope. The interface stays storage-agnostic
  so the backing store remains swappable.

The same service is reused by the indexing stage (and anywhere else needing throttling).

---

## 8. Stage 2 — Indexing

Driven by its own job, over **entities** (the cursor analogue here is "scan entities by
status"). Uses PermitService for concurrency, same as ingestion.

### Indexing job (the loop)
1. Claim a batch of entities where `status = INGESTED` OR `needsReindex = true`
   (atomic claim → `status = INDEXING`, with a lease/expiry).
2. Transform → chunk → embed → index (per path below).
3. Replace the entity's chunks in **OpenSearch** (delete old, write new — keyed by chunk id
   so re-indexing is idempotent), set `status = INDEXED`, and record `embeddingModel` +
   `chunkCount` + `indexedAt` **on the entity** (Mongo).
4. For `status = DELETED` entities: delete their chunks from **OpenSearch**, done.
5. On error: retry with backoff; after N *consecutive* failures → `FAILED` with captured error. That
   is terminal — the claim filter excludes it — and is left only by an explicit reindex or
   `POST /api/index/knowledge/{id}/retry-failed`.

### Transform paths
- **File data**: load `fileRef` → `extractFileText` (Apache Tika; OCR variant for scanned
  docs) → chunk (global config) → embed → index.
  - *File splitting is out of scope for now* (large files, or container formats like zip /
    mbox). A split step can be added here later, before extraction, without affecting the
    rest of the path.
- **Text data**: chunk (global config) → embed → index.

### Chunks live only in OpenSearch
Chunks are a **derived** artifact and are **never persisted to Mongo**. The entity (with its
retained `raw` content / `fileRef`) is the source of truth; chunks can always be regenerated
from it. Mongo only records *about* the chunks on the entity (`chunkCount`, `embeddingModel`,
`indexedAt`) — not the chunks themselves.

### Re-index without re-fetch
Because `raw` + `fileRef` are retained, bumping the chunking config or embedding model just
means flagging entities `needsReindex = true` and letting this job run again — **no source
calls**. A full OpenSearch rebuild is likewise just this stage re-run over all entities
(re-chunk + re-embed). Chunks carry `embeddingModel`, so a model mismatch is detectable.

### Embeddings
- Batch chunk texts per embedding call (throughput).
- This is the privacy-sensitive boundary — see open decision in §11.

---

## 9. Status & observability

Track per knowledge / cursor / entity: counts of fetched, ingested, indexed, failed, and
**lag** (oldest `INGESTED` entity age). Expose via metrics + the existing health endpoints.
Every failure path captures an error string and increments a retry counter; nothing fails
silently.

---

## 10. Suggested build order (phased)

1. **Model + storage**: `Knowledge`, `Cursor`, `Entity` collections + repositories (real
   Mongo adapters), `PermitService` (in-memory first).
2. **Ingestion, one connector**: `LOCAL_FS` connector with backward + forward grabbers;
   ingestion job; cursor leasing. Verify entities land in Mongo.
3. **Indexing, text + file**: Tika extraction, chunking (exists), real `EmbeddingProvider`,
   real `OpenSearchSearchIndex`. Verify chunks land in OpenSearch and search returns hits.
4. **Scheduling**: drive forward grabbers from `scheduleSettings` (Quartz/Quarkus
   scheduler). 5. **Webhooks**, deletes/tombstones, dead-letter handling, metrics.

---

## 11. Decisions (all resolved)

- **Single-user only.** The user indexes and queries their own data. No `sharings`/ACL
  model and no retrieval-time permission filter for now. (Addable later without reshaping
  the pipeline.)
- **File storage = local filesystem.** Files stay on local disk; the entity stores a
  `fileRef` (absolute path) the indexing stage reads from. No GridFS for now.
- **PermitService backing store = Redis.** Fast, TTL-based key-value — ideal for
  permits/leases (§7). Adds Redis to the infra stack.
- **File splitting = deferred.** Not implemented now; slots in before extraction later if
  needed (§8).
- **Naming = `Source`→`Knowledge`, `Document`→`Entity`**, plus the new first-class
  `Cursor`. Applies to the domain model, repositories, and Mongo collections.
- **Job mechanism = Mongo polling** for now (no extra broker infra). Stage boundaries keep
  a later swap to Kafka/RabbitMQ from touching connectors.

The design is ready to implement.
