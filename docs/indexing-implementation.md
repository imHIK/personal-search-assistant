# Indexing Subsystem — Implementation Notes

This document maps the agreed design in [`indexing-design.md`](./indexing-design.md) to the code
that implements it, and explains how to build, run, exercise, and extend the subsystem. It is the
companion "as-built" reference: where the design says *what*, this says *where* and *how*.

All packages below are under `io.personalassistant`.

---

## 1. How the two stages fit together

```
                 ┌──────────────────────────────────────────────────────────────┐
   REST  ───────►│  KnowledgeResource / IndexingResource / SearchResource (api)  │
                 └───────────────┬──────────────────────────────────┬───────────┘
                                 │ add knowledge                     │ search
                 ┌───────────────▼───────────────┐      ┌────────────▼───────────┐
                 │  DefaultKnowledgeService       │      │  DefaultSearchService  │
                 │  verify → anchor → discover    │      │  embed → retrieve →    │
                 │  → create cursors → ACTIVE     │      │  rerank → (agent)      │
                 └───────────────┬───────────────┘      └────────────┬───────────┘
                                 │ cursors                            │
   ── STAGE 1: INGESTION ────────▼──────────────────────────         │
   IngestionJob (poll AVAILABLE cursors)                             │
     → PermitService.tryAcquire (global / connector / knowledge)     │
     → CursorRepository.claim (atomic lease)                         │
     → IngestionRunner.runLease                                      │
         → SourceConnector.grab (one page)                          │
         → EntityRepository.upsert  (Mongo = source of truth)       │
         → CursorRepository.advancePosition / release               │
                                 │ entities (status=INGESTED)        │
   ── STAGE 2: INDEXING ─────────▼──────────────────────────        │
   IndexingJob (claim INGESTED / needsReindex / DELETED)            │
     → IndexingRunner.indexEntity                                   │
         → ParserRegistry (Tika / plain-text)  extract text         │
         → ChunkingStrategy  split                                  │
         → EmbeddingProvider.embedAll  vectors                      │
         → SearchIndex.indexChunks  (OpenSearch, idempotent)        │
         → EntityRepository.markIndexed                             ▼
                                          OpenSearch `chunks` alias ◄── read path
```

The two stages never block each other: each has its own poll loop, its own claim semantics, and
its own concurrency budget. Mongo is the source of truth; OpenSearch is rebuildable from it.

---

## 2. Design section → code map

| Design (`indexing-design.md`) | Implementing code |
|---|---|
| §2 Vocabulary: **Knowledge** (supersedes `Source`) | `domain.model.Knowledge` + `enums.KnowledgeStatus` |
| §2 Vocabulary: **Entity** (supersedes `Document`) | `domain.model.Entity` + `enums.EntityStatus`, `enums.EntityType` |
| §2/§4 **Cursor** (first-class) | `domain.model.Cursor` + `enums.CursorStatus`, `enums.CursorDirection` |
| §4 **Cursor position** (source-defined) | `domain.model.CursorPosition` — free-form, multi-field pagination state the connector owns |
| §2 **Iterable** | `ingestion.connector.SourceIterable` |
| §2/§5 **Grabber** (extends connector) | `ingestion.connector.SourceConnector` (`supportedDirections` + `discover` + `grab`), `GrabRequest`, `GrabPage` |
| §2/§7 **PermitService** | `common.concurrency.PermitService` + `InMemoryPermitService`, `Permit`, `ScopeLimit` |
| §3 Knowledge object + lifecycle | `app.DefaultKnowledgeService` (`add`: verify → anchor=now → discover → cursors → ACTIVE) |
| §4 Cursor states (AVAILABLE / IN_PROGRESS / IDLE / SUSPENDED / EXHAUSTED / FAILED) | `enums.CursorStatus`; transitions in `ingestion.job.IngestionRunner` |
| §4 Atomic lease + crash recovery (expired lease reclaimable) | `storage.mongo.MongoCursorRepository.claim` / `claimableFilter` (`findOneAndUpdate`) |
| §5 Backward/forward grabbers, anchor boundary | `ingestion.connector.localfs.LocalFsConnector` (`grabForward` = mtime-ordered bounded pass; `grabBackward` = path-ordered cursor-skipping DFS) |
| §5 Ingestion loop (batch=page, lease=N batches, persist→advance) | `ingestion.job.IngestionRunner.runLease` + `IngestionJob.tick` |
| §5 Forward scheduling (IDLE → AVAILABLE) | `ingestion.job.ForwardCursorScheduler` + `CursorRepository.armForwardCursors` |
| Dynamic iterables (new sub-streams over time) | `SourceConnector.hasDynamicIterables` + `ingestion.job.IterableDiscoveryScheduler` + `KnowledgeService.reconcileCursors` |
| Indexing fairness (round-robin across knowledges) | `IndexingJob.processIndexingFairly` + `EntityRepository.distinctPendingKnowledgeIds` / knowledge-scoped `claimForIndexing` |
| §5 Updates & deletes (tombstones) | `IngestionRunner.persistItem` (`markDeleted`); cleanup in `IndexingRunner.deleteEntityChunks` |
| §6 Entity document (raw + content + fileRef + index + retry) | `domain.model.Entity` (+ `Content`, `IndexInfo`, `Lease`, `Retry`); BSON in `MongoEntityRepository` |
| §6 Mongo indexes (unique `knowledgeId+externalId`, …) | `storage.mongo.MongoIndexInitializer` |
| §7 Scopes (global / connector / knowledge), TTL leases | `common.concurrency.ScopeLimit`, `InMemoryPermitService` |
| §8 Indexing loop (claim → transform → chunk → embed → index → mark) | `indexing.job.IndexingRunner.indexEntity` + `IndexingJob.tick` |
| §8 File path: `fileRef` → Tika extract | `IndexingRunner.extractText` + `indexing.parser.TikaContentParser` / `PlainTextParser` |
| §8 Text path | `IndexingRunner.extractText` (inline) |
| §8 Chunks live only in OpenSearch | `storage.search.opensearch.OpenSearchSearchIndex`; no chunk Mongo repository exists |
| §8 Re-index without re-fetch | `EntityRepository.flagNeedsReindex` → claim re-runs `IndexingRunner` |
| §8 Embeddings batched | `IndexingRunner.embed` (`app.indexing.embed-batch`) |
| §9 Status & observability | `Knowledge.Stats` (`IngestionRunner.refreshStats`), `Entity.IndexInfo`, retry counters, logging |
| §10 Phase 1 (model + storage + PermitService) | `domain.model.*`, `storage.repository.*`, `storage.mongo.*`, `common.*` |
| §10 Phase 2 (LOCAL_FS + ingestion job) | `ingestion.connector.localfs.*`, `ingestion.job.*` |
| §10 Phase 3 (Tika + chunking + embeddings + OpenSearch) | `indexing.parser.*`, `indexing.*`, `storage.search.opensearch.*` |
| §11 Single-user (no ACL filter) | search filters on `knowledgeId` only (`OpenSearchSearchIndex.filters`) |
| §11 Local filesystem storage | `Entity.Content.fileRef`; `IndexingRunner.resolve` reads from disk |
| §11 PermitService backing = Redis (eventually) | `InMemoryPermitService` today; `PermitService` interface is storage-agnostic |
| §11 Job mechanism = Mongo polling | `@Scheduled` poll loops in `IngestionJob` / `IndexingJob` |

---

## 3. Lifecycle walk-throughs

### Adding a knowledge (`POST /api/knowledge`)
1. `DefaultKnowledgeService.add` builds a `Knowledge` with `anchor = now`, status `DRAFT`.
2. `SourceConnector.verify` validates inputs/credentials (e.g. LOCAL_FS checks `rootPath` is a
   readable directory).
3. The knowledge is saved, then `connector.discover` enumerates iterables. For each iterable a
   **forward** cursor is created, plus a **backward** cursor when `backfill.enabled` — both
   `AVAILABLE`, with deterministic ids (`Ids.cursorFor`) so discovery is idempotent.
4. Status flips to `ACTIVE`; the ingestion loop picks the cursors up on its next tick.

### One ingestion lease (`IngestionRunner.runLease`)
- Resolves the `SourceIterable` for the cursor, then loops up to `batchesPerLease` pages.
- Per page: `grab` → upsert entities (`knowledgeId + externalId`, with checksum change-detection
  and tombstone handling) → `advancePosition` (persisted **after** each page) → renew the cursor
  lease and the permit heartbeat.
- Resting status: `hasMore=false` → `EXHAUSTED` (backward) or `IDLE` (forward); batch cap hit with
  more pages → `AVAILABLE`; exception → retry (`AVAILABLE`) until the limit, then `FAILED`.

### One indexing pass (`IndexingRunner.indexEntity`)
- Extract text (Tika for files via `fileRef`, inline for text) → chunk → embed (batched).
- `SearchIndex.deleteByEntity` then `indexChunks` (chunk id = `entityId_ordinal`) — an idempotent
  replace. `EntityRepository.markIndexed` records `chunkCount` + `embeddingModel` + `indexedAt`.
- Failures: retry with backoff (`status=INGESTED`, `retry.nextAttemptAt`), or terminal `FAILED`.
- Tombstones (`status=DELETED`): `deleteByEntity` + `markDeletionComplete`.

---

## 4. Build, run, test

### Prerequisites
- **JDK 21** (the Gradle toolchain enforces it).
- Docker (for local Mongo + OpenSearch).

### Start infrastructure
```bash
docker compose up -d        # MongoDB :27017, OpenSearch :9200
```

### Run the app
```bash
./gradlew quarkusDev        # dev mode with live reload
```
On startup `MongoIndexInitializer` ensures the Mongo indexes and `OpenSearchIndexInitializer`
creates `chunks_v1` + the `chunks` alias (it logs a warning and continues if OpenSearch is down).

### Run the tests
```bash
./gradlew test
```
The unit tests use in-memory fakes (`src/test/java/io/personalassistant/testsupport`) and need
**no** running Mongo or OpenSearch. They cover: PermitService ceilings/composite acquire, RRF
fusion, fixed-size chunking, deterministic embeddings, `LocalFsConnector` paging, and full
`IngestionRunner` / `IndexingRunner` flows (including retry and tombstone paths).

---

## 5. REST API

| Method & path | Purpose |
|---|---|
| `POST /api/knowledge` | Register a knowledge (validates, discovers, creates cursors, activates) |
| `GET /api/knowledge` / `GET /api/knowledge/{id}` | List / fetch knowledge |
| `POST /api/knowledge/{id}/pause` / `.../resume` | Pause or resume scheduling |
| `DELETE /api/knowledge/{id}` | Soft-delete + tear down chunks, entities, cursors |
| `POST /api/index/knowledge/{id}/sync` | Re-arm forward cursors now (incremental trigger) |
| `POST /api/index/entities/{id}/reindex` | Flag one entity for re-index (no re-fetch) |
| `DELETE /api/index/entities/{id}` | Tombstone an entity (chunks removed by the indexing stage) |
| `POST /api/search` | Hybrid / lexical / semantic search |

### Example: add a local-filesystem knowledge
```bash
curl -X POST localhost:8080/api/knowledge -H 'Content-Type: application/json' -d '{
  "name": "My Documents",
  "type": "LOCAL_FS",
  "inputs": { "rootPath": "/home/me/Documents" },
  "backfillEnabled": true
}'
```

### Example: search
```bash
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' -d '{
  "query": "quarterly revenue",
  "mode": "HYBRID",
  "topK": 10
}'
```

---

## 6. Configuration reference (`application.properties`)

| Key | Default | Meaning |
|---|---|---|
| `app.ingestion.poll-interval` | `30s` | Ingestion loop tick |
| `app.ingestion.poll-batch` | `20` | Claimable cursors examined per tick |
| `app.ingestion.batches-per-lease` | `50` | Pages fetched per cursor lease before releasing |
| `app.ingestion.max-items-per-batch` | `100` | Soft cap on items per grabber page |
| `app.ingestion.lease-seconds` | `900` | Cursor lease duration; must exceed the worst-case single-page fetch time (the per-page renew covers multi-page leases; writes are lease-fenced) |
| `app.ingestion.retry-limit` | `5` | Cursor failures before `FAILED` |
| `app.ingestion.permits.global` / `.connector` / `.knowledge` | `8` / `4` / `2` | Scoped ingestion concurrency ceilings |
| `app.ingestion.permits.ttl-seconds` | `900` | Ingestion permit TTL; renewed per page like the lease, so keep `>= app.ingestion.lease-seconds` |
| `app.scheduler.forward-interval` | `60m` | How often forward cursors are re-armed |
| `app.scheduler.discovery-interval` | `60m` | How often dynamic-iterable sources are re-discovered (new folders/channels) |
| `app.indexing.poll-interval` | `5s` | Indexing loop tick |
| `app.indexing.batch` | `20` | Global budget of entities indexed per tick |
| `app.indexing.per-knowledge` | `5` | Per-knowledge claim quota per tick (round-robin fairness) |
| `app.indexing.max-knowledges` | `200` | Cap on distinct knowledges scanned per indexing tick |
| `app.indexing.concurrency` | `4` | Global indexing concurrency ceiling |
| `app.indexing.permits.ttl-seconds` | `300` | Indexing permit TTL; held for a whole tick (not renewed mid-tick), so size above the worst-case tick time |
| `app.indexing.embed-batch` | `64` | Chunks per embedding call |
| `app.indexing.lease-seconds` | `120` | Entity indexing lease duration |
| `app.indexing.retry-limit` | `5` | Indexing failures before `FAILED` |
| `app.indexing.backoff-seconds` | `30` | Delay before a failed entity is re-claimable |
| `app.chunking.size` / `.overlap` | `1000` / `150` | Fixed-size chunk window + overlap |
| `app.embedding.model` / `.dimension` | `local-hashing-v1` / `384` | Embedding identity; **must** match the OpenSearch `knn_vector` mapping |

---

## 7. Extension points

| To add… | Implement… | Register via |
|---|---|---|
| A new data source (Slack, Drive, Gmail) | `ingestion.connector.SourceConnector` | CDI bean; auto-discovered by `CdiConnectorRegistry` keyed on `SourceType` |
| A new file type / extractor (OCR, etc.) | `indexing.parser.ContentParser` (set `priority()`) | CDI bean; selected by `CdiParserRegistry` |
| A different chunking strategy | `indexing.chunking.ChunkingStrategy` | replace the `@ApplicationScoped` bean |
| A real embedding model (ONNX / hosted) | `indexing.embedding.EmbeddingProvider` | replace the bean; update `app.embedding.*`, then re-index |
| A different vector store / search engine | `storage.search.SearchIndex` | new adapter package |
| A multi-node permit limiter | `common.concurrency.PermitService` (Redis) | replace `InMemoryPermitService` |

Adding a connector is the common case and the SPI is deliberately permissive so each integration
can work the way its source does:

- **`supportedDirections()`** — declare which of `BACKWARD`/`FORWARD` the source supports. A
  forward-only or stream/webhook-only source returns just `FORWARD`, and the generic flow
  (`DefaultKnowledgeService`) only creates the cursors it declares — no backfill cursor is made.
- **`discover()`** — return whatever iterables make sense: many sub-streams (channels, folders,
  labels) or a single one if the source has no natural partitioning.
- **`grab(GrabRequest)`** — paginate however the source works. The connector **owns its pagination
  state** via `CursorPosition`: a free-form, multi-field bag (`{"pageToken": …}`, `{"offset": …}`,
  `{"sinceMillis": …, "lastId": …}`, a change-id, an mbox byte offset, …). The core never
  interprets it — it just persists the `nextPosition` you return and hands it back on the next page.
  Build positions with `CursorPosition.builder().put(…).build()` and read them with the typed
  accessors (`getString`/`getLong`/`getInt`). `GrabRequest` is an object (not a parameter list) so
  new optional inputs can be added later without breaking existing connectors.

Implement those plus `type()` and `verify()`, annotate `@ApplicationScoped`, and add the enum
constant to `SourceType`. Nothing else changes — the ingestion loop, cursors, permits, indexing and
search are all source-agnostic. `LocalFsConnector` is a worked example: it defines its position as
`{"lastModifiedMillis": <long>, "path": <string>}`.

---

## 8. Deferred (next increments)

These are intentionally out of scope for this build (per the design's phasing) and have their
seams already in place:

- **Per-knowledge cron scheduling.** `ForwardCursorScheduler` currently re-arms forward cursors on
  a fixed interval (`app.scheduler.forward-interval`) for every active, schedule-enabled knowledge.
  The next step is to honour each knowledge's `scheduleSettings.cron` (e.g. via the Quartz-backed
  Quarkus scheduler). The re-arm primitive (`armNow` / `armForwardCursors`) is what it will call.
- **Webhooks.** `ForwardCursorScheduler.armNow(knowledgeId)` is the on-demand trigger a webhook
  endpoint would invoke; the webhook config already lives on `Knowledge.Config.webhookSettings`.
- **File splitting** for very large or container files (zip / mbox) — slots into
  `IndexingRunner.extractText` before extraction.
- **Metrics endpoints** beyond the current counters/logging (e.g. Micrometer gauges for lag and
  queue depth, surfaced through the existing health/metrics infrastructure).
- **Redis-backed `PermitService`** for multi-node deployments.
- **A real semantic embedding model** in place of the offline hashing baseline.
