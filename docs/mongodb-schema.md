# MongoDB Schema — source of truth

MongoDB holds the **canonical** records. OpenSearch is derived from these and can be
rebuilt at any time by replaying Mongo. Database: `personal_assistant`.

Design goals: easy incremental sync, full reprocessing from source of truth, and clean
support for many heterogeneous sources without schema churn.

Five collections: **`knowledge`**, **`entities`**, **`cursors`**, **`connections`**, **`discovery`**.
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
    "chunking":         { "strategy": null, "maxSize": null, "overlap": null, "separators": null }
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
  "index": {
    "chunkCount": 12,
    "embeddingModel": "bge-base-en-v1.5",
    "indexedAt": "…",
    "error": null
  },
  "lease": { "owner": "worker-1", "expiresAt": "…" },   // indexing-stage claim
  "retry": { "count": 0, "nextAttemptAt": null },
  "lastSeenGeneration": 3,                          // vs knowledge.syncGeneration → staleness mark
  "createdAt": "…",
  "updatedAt": "…"
}
```

Indexes:
- `{ knowledgeId: 1, externalId: 1 }` **unique** — this is what makes upsert dedupe work.
- `{ status: 1 }` and `{ knowledgeId: 1, status: 1 }` — find work to (re)process, with fairness.
- `{ needsReindex: 1 }` — the explicit re-index queue.
- `{ "retry.nextAttemptAt": 1 }` — backoff-gated re-claim.

> **`checksum` is the only change signal.** A connector must make it change whenever the item
> changes (`LOCAL_FS`: `size:<n>;mtime:<millis>`; Drive: `version`/`md5Checksum`; Gmail:
> `gmail:<id>;hist:<historyId>`). An unchanged checksum on an `INDEXED` entity is skipped entirely —
> no parse, no embed, no OpenSearch write.

---

## Collection: `cursors`
One document per `(knowledgeId, iterableId, direction)` — the walk state for one grabber over one
sub-stream. The id is *derived* from that triple, so discovery and re-arm are idempotent.

```jsonc
{
  "_id": "cur_kn_8f3a...:folder:/home/me/Documents:FORWARD",
  "knowledgeId": "kn_8f3a...",
  "iterableId": "folder:/home/me/Documents",
  "attributes": { },                    // connector-supplied iterable metadata
  "direction": "FORWARD",               // CursorDirection — FORWARD | BACKWARD
  "position": { "lastModifiedMillis": 1718877600000, "path": "…" },  // free-form, connector-owned
  "status": "AVAILABLE",                // CursorStatus
  "lease":  { "owner": "worker-1", "expiresAt": "…" },
  "retry":  { "count": 0, "lastError": null },
  "stats":  { "lastRunAt": "…", "fetched": 1240 },
  "scope":  { "connectorType": "LOCAL_FS" }
}
```

Indexes: `{ knowledgeId: 1 }`, `{ status: 1 }`, `{ knowledgeId: 1, direction: 1, status: 1 }`.

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
  "isDefault": true,                    // at most one default per type
  "status": "ACTIVE",                   // ConnectionStatus
  "lastError": null,
  "createdAt": "...",
  "updatedAt": "..."
}
```

Indexes: `{ type: 1 }`, `{ type: 1, isDefault: 1 }`.

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
  the work queue.
- **Idempotency**: chunk ids are derived (`entityId_ordinal`) so re-runs overwrite
  cleanly rather than duplicating. Indexing is a `deleteByEntity` + `indexChunks` replace.

> Collections map to repository ports (`KnowledgeRepository`, `EntityRepository`, `CursorRepository`,
> `ConnectionRepository`, `DiscoveryStatusRepository`) so swapping Mongo for another store later
> touches only `storage.mongo`. There is **no chunk repository** — chunks live in OpenSearch.
