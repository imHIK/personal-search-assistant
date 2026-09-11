# MongoDB Schema — source of truth

MongoDB holds the **canonical** records. OpenSearch is derived from these and can be
rebuilt at any time by replaying Mongo. Database: `personal_assistant`.

Design goals: easy incremental sync, full reprocessing from source of truth, and clean
support for many heterogeneous sources without schema churn.

Eight collections: **`knowledge`**, **`entities`**, **`cursors`**, **`connections`**, **`discovery`**, **`digests`**, **`digestRuns`**, **`tasks`**.
Chunks are deliberately *not* a Mongo collection — see below. All indexes are created at startup by
`MongoIndexInitializer` (`@Observes StartupEvent`); there is **no migration framework**, so a new
query pattern means adding its index there.

---

## Collection: `knowledge`
One document per connected, configured source instance (a folder, a mailbox, a Drive scope).

```jsonc
{
  "_id": "kn_8f3a...",
  "name": "My Documents",
  "connectorDetails": {
    "type": "LOCAL_FS",              // SourceType enum — IMMUTABLE after creation
    "connectionId": "conn_1b2c...",  // reusable credentials; null for no-auth sources
    "auth": { }                      // per-knowledge auth blob (legacy/inline path)
  },
  "inputs": { "rootPath": "/home/me/Documents" },  // connector-specific, opaque to core
  "config": {
    "scheduleSettings": { "cron": null, "interval": "1h", "enabled": true },
    "webhookSettings":  { "enabled": false, "secret": null },
    "backfill":         { "enabled": true },
    "chunking":         { "strategy": null, "maxSize": null, "overlap": null, "separators": null },
    "retention":        { "period": null }             // null = never expire (see below)
  },
  "anchor": "2026-06-20T10:00:00Z",   // the forward/backward boundary — NEVER moves
  "nextSyncDueAt": "2026-06-20T11:00:00Z",
  "status": "ACTIVE",                  // KnowledgeStatus
  "lastError": null,
  "stats": { },
  "syncGeneration": 3,                 // bumped on a membership-changing edit
  "createdAt": "...",
  "updatedAt": "..."
}
```

Indexes: `{ status: 1 }`, `{ "connectorDetails.type": 1 }`, `{ "connectorDetails.connectionId": 1 }`.

> **`config.retention.period` is opt-in and defaults to null.** A null window at every tier —
> knowledge, connector `defaultRetention()`, then the global `app.retention.default-period` — means
> *never expire*, which is what a document corpus must do. Only feed-like sources (the ATS job
> boards) ship a connector-level default. See [`knowledge-lifecycle.md`](./knowledge-lifecycle.md).

> `inputs` and `connectorDetails.auth` are intentionally free-form sub-documents. Each
> `SourceConnector` reads its own keys; the core never inspects them. This is the seam that lets new
> source types arrive without schema migrations.
>
> **`anchor` is the central invariant.** It is stamped once at creation and never moves, including
> across edits. Forward grabs return items `>= anchor`, backward (backfill) grabs `< anchor`.
> Moving it would create gaps or duplicates.
>
> `config.chunking` is *inherit-by-default*: null fields fall through to `app.chunking.*`. Changing
> it is a direct update — new chunks use the new spec, already-indexed chunks are left alone until
> an explicit `POST /api/index/entities/{id}/reindex`.

---

## Collection: `entities`
One document per ingested item (a file, an email, a message).

```jsonc
{
  "_id": "ent_8f3a...",
  "knowledgeId": "kn_8f3a...",
  "iterableId": "folder:/home/me/Documents",       // which sub-stream it came from
  "entityType": "FILE",                             // EntityType enum
  "externalId": "/home/me/Documents/report.pdf",   // natural key within the knowledge
  "raw": { },                                       // connector's untouched payload
  "content": {
    "text": null,                                   // inline text, OR…
    "fileRef": "/tmp/psa-drive/report.pdf"          // …a path. Never bytes — Mongo's 16 MB cap
  },
  "metadata": { "title": "Q2 Report", "contentType": "application/pdf", "sizeBytes": 24576 },
  "checksum": "size:24576;mtime:1718877600000",     // the ONLY change signal
  "status": "INDEXED",                              // EntityStatus
  "needsReindex": false,
  "needsRefetch": false,                            // stored content is a staged copy we distrust
  "index": {
    "chunkCount": 12,
    "embeddingModel": "bge-base-en-v1.5",
    "indexedAt": "…",
    "error": null
  },
  "lease": { "owner": "worker-1", "expiresAt": "…" },   // indexing-stage claim
  "retry": { "count": 0, "nextAttemptAt": null },
  "lastSeenGeneration": 3,                          // vs knowledge.syncGeneration → staleness mark
  "expiresAt": null,                                // source-declared end date, or null
  "createdAt": "…",
  "updatedAt": "…"
}
```

Indexes:
- `{ knowledgeId: 1, externalId: 1 }` **unique** — this is what makes upsert dedupe work.
- `{ status: 1 }` and `{ knowledgeId: 1, status: 1 }` — find work to (re)process, with fairness.
- `{ needsReindex: 1 }` — the explicit re-index queue.
- `{ "retry.nextAttemptAt": 1 }` — backoff-gated re-claim.
- `{ expiresAt: 1 }` — the retention sweeper's source-declared-expiry pass, which is a global scan.
- `{ knowledgeId: 1, createdAt: 1 }` — its per-knowledge retention-window pass.
- `{ knowledgeId: 1, updatedAt: -1, _id: 1 }` and `{ knowledgeId: 1, status: 1, updatedAt: -1, _id: 1 }`
  — the sorted listing behind `GET /api/knowledge/{id}/entities`, unfiltered and status-filtered.
  `_id` is the paging tiebreak so two entities touched in the same millisecond can't swap places
  between pages. Note `{ knowledgeId: 1, status: 1 }` is now a strict prefix of the second one and
  therefore redundant; `MongoIndexInitializer` only ever creates indexes (no drop path, no migration
  framework), so it is deliberately left in place rather than removed.

> **Listing reads a projection.** `EntityRepository.findByKnowledge` returns `EntitySummary`, not
> `Entity` — `raw` and `content.text` are the bulk of the document and a table view needs neither.
> The sort key is `updatedAt`, and `stampLastSeen` deliberately does *not* bump it, so a membership
> re-walk doesn't reshuffle the browser. `updatedAt` is the row's last write from *either* stage and
> most of its movement is the indexer's own bookkeeping — a claim, a retry, a deferral — so it is a
> sort key, not a content date. The projection carries `createdAt` alongside it for that: it is
> `setOnInsert` only, so it is the only honest answer to "when did this arrive", and it is what the
> console shows as an item's *added* time. Offset paging can still drift a page boundary if the
> indexing job touches entities mid-scan; a refresh re-reads, which is fine for a console.

> **`expiresAt` is ingestion-owned and usually null.** It is written by `upsert` only when the source
> states a real end date (Ashby's `closedAt`; Greenhouse and Lever publish none), and it beats the
> knowledge-level retention window when present. Ageing out is measured from **`createdAt`**, never
> `updatedAt`: an item that has sat unchanged is exactly the case retention exists for, so a
> change-based clock would never fire on it.
>
> There is deliberately **no Mongo TTL index** on this collection. `expireAfterSeconds` would drop the
> document without routing through `deleteByEntity`, permanently orphaning its chunks in OpenSearch,
> and would bypass lease fencing. `RetentionSweeper` tombstones instead, and the ordinary deletion
> path removes the chunks.

> **`checksum` is the only change signal.** A connector must make it change whenever the item
> changes (`LOCAL_FS`: `size:<n>;mtime:<millis>`; Drive: `version`/`md5Checksum`; Gmail:
> `gmail:<id>;hist:<historyId>`). An unchanged checksum is skipped entirely — no parse, no embed, no
> OpenSearch write — unless the entity is `FAILED` or `DELETED`, the two statuses where a re-walk is a
> deliberate way back in, or `needsRefetch` is set.
>
> `needsRefetch` is the only one of those three that is not about the source. It says the *stored*
> content is a staged copy we no longer trust, so "unchanged at the source" is exactly the case it
> has to override; `upsert` clears it in the same write that stores the fresh bytes.
>
> The skip covers `INGESTED` and `INDEXING`, not just `INDEXED`, because `upsert` owns the indexing
> queue reset: it zeroes `retry` and clears `retry.nextAttemptAt`. Re-upserting an entity that is merely
> still queued would therefore throw away a rate-limit deferral's reopening instant on every poll, make
> the entity immediately claimable, and have it parsed and chunked again for nothing.

---

## Collection: `cursors`
One document per `(knowledgeId, iterableId, direction)` — the walk state for one grabber over one
sub-stream. The id is *derived* from that triple, so discovery and re-arm are idempotent.

```jsonc
{
  "_id": "cur_kn_8f3a...:folder:/home/me/Documents:FORWARD",
  "knowledgeId": "kn_8f3a...",
  "iterableId": "folder:/home/me/Documents",
  "iterableName": "Documents",          // discover()'s display name — what the console shows
  "attributes": { },                    // connector-supplied iterable metadata
  "direction": "FORWARD",               // CursorDirection — FORWARD | BACKWARD
  "position": { "lastModifiedMillis": 1718877600000, "path": "…" },  // free-form, connector-owned
  "status": "AVAILABLE",                // CursorStatus
  "lease":  { "owner": "worker-1", "expiresAt": "…" },
  "retry":  { "count": 0, "lastError": null, "nextAttemptAt": null },
  "stats":  { "lastRunAt": "…", "fetched": 1240 },
  "scope":  { "connectorType": "LOCAL_FS" }
}
```

Indexes: `{ knowledgeId: 1 }`, `{ status: 1 }`, `{ knowledgeId: 1, direction: 1, status: 1 }`,
`{ retry.nextAttemptAt: 1 }`.

> **Rate-limit holds.** `retry.nextAttemptAt` is set only alongside `status: "RATE_LIMITED"`: it is
> when the limiter says the source's quota reopens, and the claim filter skips the cursor until then.
> Nothing writes the status back — the instant simply stops excluding the row, so the timestamp is
> the single source of truth. Note the deliberate difference from `entities`, where a null
> `retry.nextAttemptAt` means "no backoff, claim it": on a `RATE_LIMITED` cursor a null matches
> nothing, so every reset (`release`, `retryFailedByKnowledge`, `revive`, `resetToStart`) clears the
> whole `retry` block rather than leaving a stale instant behind.

> **`iterableName` is cosmetic and refreshed, not fenced.** It is snapshotted from
> `SourceIterable.displayName()` when the cursor is created and re-written by the reconcile pass
> (`rename`, an unfenced single-field update on any status) whenever `discover` reports a different
> name. That is what backfills cursors written before the field existed and what follows a renamed
> Drive folder or Gmail label. It is null on a legacy cursor until the next reconcile, so the console
> keeps its id-shortening fallback.

> **Lease fencing.** `advancePosition` / `release` / `recordFailure` are compare-and-set on
> `lease.owner` **and** not-expired. A worker whose lease expired gets `false` back and must stop
> touching the cursor immediately — that is what keeps a reclaimed cursor from being corrupted by
> the previous owner. `app.ingestion.lease-seconds` must comfortably exceed the worst-case time to
> fetch and persist a *single* page.
>
> `position` is opaque to the core: a page token, an offset, a `(timestamp, id)` keyset — whatever
> the source needs. It is persisted verbatim and handed back on the next page.

---

## Collection: `connections`
Reusable credentials for a `SourceType`, shared across knowledges. Kept separate from `knowledge`
so re-authenticating one account doesn't mean editing every knowledge that uses it.

```jsonc
{
  "_id": "conn_1b2c...",
  "name": "Personal Google account",
  "type": "GMAIL",                      // SourceType
  "auth":   { "refreshToken": "…", "accessToken": "…", "expiresAt": "…" },
  "config": { },
  // Outbound call ceilings for this account. Absent (or an empty rules array) means the
  // app.ratelimit.* default applies. A call must satisfy every rule; each is a token bucket
  // whose capacity is `permits`, so there is no separate burst field.
  "rateLimit": { "rules": [ { "permits": 10, "windowSeconds": 1 },
                            { "permits": 500, "windowSeconds": 60 } ] },
  "isDefault": true,                    // at most one default per type
  "status": "ACTIVE",                   // ConnectionStatus
  "lastError": null,
  "createdAt": "...",
  "updatedAt": "..."
}
```

Indexes: `{ type: 1 }`, `{ type: 1, isDefault: 1 }`. `rateLimit` is deliberately unindexed — it is
only ever read alongside the connection that carries it, never queried on.

> Resolved by `ConnectionResolver`: a knowledge's explicit `connectorDetails.connectionId` wins,
> otherwise the default for its `SourceType`. `DefaultGoogleAccessTokens` writes refreshed tokens
> back onto the connection, so the refresh is shared rather than repeated per knowledge.

---

## Collection: `discovery`
One document per `(knowledgeId, direction)` — the observability record for a single **grabber's**
discovery step (the `connector.discover` call that enumerates the iterables a grabber walks). A
knowledge runs up to two grabbers — a **backward** (backfill) and a **forward** (incremental) one —
so it has up to two discovery records. Each is overwritten with the latest outcome on each run, while
the `runCount`/`failureCount` counters accumulate. This closes a visibility gap: previously only an
*activation-time* discovery failure was recorded (as `knowledge.status = ERROR`); the recurring
reconcile discovery (`IterableDiscoveryScheduler` → `reconcileCursors`) left no trace, so there was
no way to check, per grabber, when discovery last ran, what it found, or why it failed.

```jsonc
{
  "_id": "dsc_kn_8f3a...:FORWARD",   // dsc_<knowledgeId>:<DIRECTION>
  "knowledgeId": "kn_8f3a...",
  "direction": "FORWARD",             // CursorDirection — BACKWARD | FORWARD (one record per grabber)
  "lastOutcome": "OK",                // OK | FAILED (DiscoveryOutcome)
  "lastTrigger": "RECONCILE",         // ACTIVATION | RECONCILE (DiscoveryTrigger)
  "lastRunAt": "2026-06-28T10:00:00Z",
  "iterablesFound": 12,               // from the last SUCCESSFUL discover (left intact on failure)
  "lastCounts": { "created": 2, "revived": 0, "retired": 1 },  // THIS grabber's cursors changed, last OK run
  "runCount": 37,                     // total discovery runs (success + failure)
  "failureCount": 1,                  // total failed runs
  "lastError": null,                  // compact reason when lastOutcome = FAILED, else null
  "createdAt": "...",
  "updatedAt": "..."
}
```

Indexes: `{ knowledgeId: 1 }`, `{ direction: 1 }`, `{ lastOutcome: 1 }`.

> A record exists for each grabber the knowledge actually runs: a forward grabber whenever the source
> supports it, and a backward grabber only when the source supports it **and** backfill is enabled.
> `iterablesFound` is the same across a knowledge's grabbers (one `discover` feeds both), while
> `lastCounts` is attributed per direction.
>
> `record` is an atomic upsert with `$inc` counters, so repeated runs accumulate correctly without a
> read-modify-write race. A `FAILED` run leaves `iterablesFound`/`lastCounts` untouched, so a failure
> never clobbers the last known-good snapshot. The records are torn down with their knowledge (the
> knowledge-delete cascade calls `DiscoveryStatusRepository.deleteByKnowledge`).

---

## Collections: `digests` and `digestRuns`

A saved search plus a schedule, and one document per execution. Documented in
[`digests.md`](./digests.md); the schema-relevant points are:

- `digests` is indexed on `(enabled, nextRunAt)` — the scheduler's due query. A **null `nextRunAt`
  means "due now"**, so a freshly created digest runs on the next tick rather than one interval later.
- `digestRuns` is indexed on `(digestId, ranAt desc)` and stores a **projection** of each hit — entity
  id, chunk id, title, uri, score, snippet, and `annotations` — never the full chunk text, which the
  entity already holds.
- `items[].annotations` is an **open map**: what the digest's task said about that specific result,
  keyed by whatever the task asked the model to record. The keys come from user-written tasks, so
  typing them would mean a schema change per question anyone wants asked.
- A run also carries `candidates`, `suppressed` and `outsideWindow` — what the search returned, how
  many were dropped as already reported, and (only when the search returned nothing) what the same
  search finds with the look-back window removed. Together they separate "nothing matched" from
  "everything matched was already seen" and from "the window is empty but the corpus is not"; all
  three are absent on runs written before they existed and read back as `items.size()`, `0` and `0`.
- `digests.historyResetAt` bounds the newness read: runs before it are ignored when working out what
  has already been reported, so the seen-set can be cleared without deleting the history that is also
  the audit trail.
- A run is also the newness record: an item is new when it is absent from every earlier run, keyed on
  `items.entityId`. A chunk id changes when a document is re-chunked, so the entity is the only stable
  key for "the user has seen this".
- A **failed** run is still written, carrying `error` and no items. That is what makes a digest that has
  been erroring visible rather than merely quiet, and an empty `items` array means it cannot suppress
  anything later.
- History is unbounded — see [L8](./limitations.md).

---

## Collection: `tasks`

User-written LLM tasks — an instruction a digest runs over its results. Documented in
[`tasks.md`](./tasks.md); the schema-relevant points are:

- **No index is declared.** Every access is by `_id` or a full list of what is a hand-written,
  human-sized collection, and both are already served. This is the one collection
  `MongoIndexInitializer` deliberately says nothing about.
- Ids carry a `task_` prefix. That is load-bearing rather than cosmetic: it is what routes a lookup to
  this collection instead of the bundled catalogue, and so what stops a user task shadowing a built-in
  slug like `answer`.
- Bundled tasks are **not** here. They live in `config/prompts.json`, are validated at boot, and are
  read-only — a malformed one must stop the application, which a runtime row cannot be held to.
- `mode` decides which of two disjoint field sets is meaningful: `SIMPLE` uses `instruction` /
  `output` / `fields`, `RAW` uses `system` / `user`. The unused half is stored null rather than
  dropped, so switching modes in the console does not lose what was typed.

---

## Chunks — NOT a Mongo collection

Chunks are **not stored in Mongo**. They are a derived artifact produced at indexing time
and written **only to OpenSearch** (see `indexing-design.md` §8 and `opensearch-index.md`).
The entity's retained content/`fileRef` is the source of truth, so chunks can always be
regenerated. The entity records only metadata *about* its chunks: `chunkCount`,
`embeddingModel`, `indexedAt`.

---

## Lifecycle & sync notes

- **Upsert by `(knowledgeId, externalId)`**; compare `checksum` to decide skip vs re-index.
- **Deletes**: when a connector reports an item gone, tombstone the entity, then delete
  its chunks from **OpenSearch** (chunks are not in Mongo).
- **Reprocessing**: entities in `status = FAILED` / `INGESTED`, or with `needsReindex = true`, are
  the indexing work queue; `needsRefetch = true` is the *ingestion* one.
- **`needsRefetch`** says the stored `content.fileRef` is a staged copy that may no longer exist —
  set by a knowledge re-index of a `FETCH_AND_REINDEX` connector, and by the indexer when it finds
  the file gone. It is the only escape from the checksum skip, so the walk re-materializes the item
  even though the source is unchanged, and `upsert` clears it in the same write. Ingestion-owned,
  like `content` and `checksum` (field ownership, invariant 8). No index: the bulk flag is scoped to
  one knowledge and is served by `{ knowledgeId: 1, status: 1 }`. See `limitations.md` L11.
- **Idempotency**: chunk ids are derived (`entityId_ordinal`) so re-runs overwrite
  cleanly rather than duplicating. Indexing is a `deleteByEntity` + `indexChunks` replace.

> Collections map to repository ports (`KnowledgeRepository`, `EntityRepository`, `CursorRepository`,
> `ConnectionRepository`, `DiscoveryStatusRepository`) so swapping Mongo for another store later
> touches only `storage.mongo`. There is **no chunk repository** — chunks live in OpenSearch.
