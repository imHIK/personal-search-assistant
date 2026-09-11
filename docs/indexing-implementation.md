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
         → ParserRegistry (per-format, Tika fallback)  extract     │
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
| §2/§5 **Grabber** (extends connector) | `ingestion.connector.SourceConnector` (`supportedDirections` + `discover` + `grab`), `GrabContext`, `GrabResult`, and the `TokenWindowGrabber` / `TimeWindowGrabber` bases |
| §2/§7 **PermitService** | `common.concurrency.PermitService` + `InMemoryPermitService`, `Permit`, `ScopeLimit` |
| §3 Knowledge object + lifecycle | `app.DefaultKnowledgeService` (`add`: verify → anchor=now → discover → cursors → ACTIVE) |
| §4 Cursor states (AVAILABLE / IN_PROGRESS / IDLE / SUSPENDED / EXHAUSTED / FAILED) | `enums.CursorStatus`; transitions in `ingestion.job.IngestionRunner` |
| §4 Atomic lease + crash recovery (expired lease reclaimable) | `storage.mongo.MongoCursorRepository.claim` / `claimableFilter` (`findOneAndUpdate`) |
| §5 Backward/forward grabbers, anchor boundary | `ingestion.connector.localfs.LocalFsConnector` (`grabForward` = mtime-ordered bounded pass; `grabBackward` = path-ordered cursor-skipping DFS) |
| §5 Ingestion loop (batch=page, lease=N batches, persist→advance) | `ingestion.job.IngestionRunner.runLease` + `IngestionJob.tick` |
| §5 Forward scheduling (IDLE → AVAILABLE) | `ingestion.job.ForwardCursorScheduler` + `CursorRepository.armForwardCursors` |
| Per-source schedule (custom → connector default → global) | `ingestion.schedule.ScheduleResolver` (+ `domain.model.SyncSchedule`, `SourceConnector.defaultSchedule`, `Knowledge.nextSyncDueAt`). Each tick arms only knowledges whose `nextSyncDueAt` has arrived, then rolls it forward by the resolved interval/cron |
| Dynamic iterables (new sub-streams over time) | `SourceConnector.hasDynamicIterables` + `ingestion.job.IterableDiscoveryScheduler` + `KnowledgeService.reconcileCursors` |
| Indexing fairness (round-robin across knowledges) | `IndexingJob.processIndexingFairly` + `EntityRepository.distinctPendingKnowledgeIds` / knowledge-scoped `claimForIndexing` |
| §5 Updates & deletes (tombstones) | `IngestionRunner.persistItem` (`markDeleted`); cleanup in `IndexingRunner.deleteEntityChunks` |
| §6 Entity document (raw + content + fileRef + index + retry) | `domain.model.Entity` (+ `Content`, `IndexInfo`, `Lease`, `Retry`); BSON in `MongoEntityRepository` |
| §6 Mongo indexes (unique `knowledgeId+externalId`, …) | `storage.mongo.MongoIndexInitializer` |
| §7 Scopes (global / connector / knowledge), TTL leases | `common.concurrency.ScopeLimit`, `InMemoryPermitService` |
| §8 Indexing loop (claim → transform → chunk → embed → index → mark) | `indexing.job.IndexingRunner.indexEntity` + `IndexingJob.tick` |
| §8 File path: `fileRef` → per-type extract | `IndexingRunner.extract` + `indexing.parser.*` (PDF/Word/PPT/Excel/HTML/PlainText, Tika fallback) — see [`parsing-and-chunking.md`](./parsing-and-chunking.md) |
| §8 Text path | `IndexingRunner.extract` (inline) |
| §8 Chunking (pluggable, per-knowledge) | `indexing.chunking.*` — `ChunkingStrategyRegistry` + `ChunkingSpecResolver`; strategy chosen from `Knowledge.config.chunking` per pass (direct update, no re-chunk) |
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
- A re-ingest is a **field-level** upsert, not a document replace: it writes only what ingestion owns
  (content, checksum, `lastSeenGeneration`), resets the work queue, and **drops any lease**. That last
  part is what fences out an indexer still running on the previous revision — see below.

### One indexing pass (`IndexingRunner.indexEntity`)
- Extract text (Tika for files via `fileRef`, inline for text) → chunk → embed (batched).
- The embedded vectors are validated against the chunk list before anything is written: a provider
  that returns a short list, or a hole where two payload entries claimed the same index, fails the
  pass. A chunk indexed without a vector is accepted by OpenSearch and then invisible to semantic
  search forever, so this is deliberately a loud failure rather than a partial success.
- `SearchIndex.deleteByEntity` then `indexChunks` (chunk id = `entityId_ordinal`) — an idempotent
  replace. A bulk that OpenSearch partly rejects throws, so `markIndexed` is never reached with a
  chunk count the index does not actually hold.
- `EntityRepository.markIndexed` records `chunkCount` + `embeddingModel` + `indexedAt`, and clears
  the retry streak (`retry.count` is *consecutive* failures, not lifetime ones).
- Failures: retry with backoff (`status=INGESTED`, `retry.nextAttemptAt`), or terminal `FAILED`.
- Tombstones (`status=DELETED`): `deleteByEntity` + `markDeletionComplete`.

### Entity lease fencing and the dead-letter state
- `markIndexed` / `markFailed` / `markDeletionComplete` all take the claiming worker's id and are
  **compare-and-set on `(id, lease.owner, live lease)`**, exactly like the cursor writes. They return
  `false` when the lease was lost; the runner logs and stops, leaving the entity to its new owner. No
  compensation is attempted — chunk ids are `entityId_ordinal`, so the new owner's replace overwrites
  anything the fenced-out worker wrote.
- `FAILED` is a genuine dead-letter: `markFailed` clears `needsReindex` on the terminal arm and the
  claim filter excludes `FAILED` outright, so a dead-lettered entity leaves the work queue instead of
  being re-claimed on every tick.
- Two ways back in, both explicit: `POST /api/index/entities/{id}/reindex` (single entity) and
  `POST /api/index/knowledge/{id}/retry-failed` (everything dead-lettered in a knowledge, cursors
  included). Both restore a fresh retry budget.
- One dead letter is terminal on the *first* attempt rather than after the ladder: an entity whose
  staged `fileRef` no longer exists. No backoff can make the file reappear, so the same write sets
  `needsRefetch=true`, which is what lets a later walk re-materialize it — see §5.

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
creates `chunks_v3_768` + the `chunks` alias (it logs a warning and continues if OpenSearch is down).

The shipped embedding provider is `openai-embed` (hosted, keyed off `GEMINI_API_KEY`). The
alternative `onnx-bge` needs an exported model directory and ships with
`app.embedding.onnx.model-path` empty, so selecting it throws on the first embed until you set it.
For local dev with neither, set `app.embedding.provider=local-hashing`. See
[`providers.md`](./providers.md).

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
| `GET /api/knowledge/{id}/entities` | Page its entities newest-first (`status`, `limit` ≤ 200, `offset`); returns projections, not full entities |
| `GET /api/knowledge/{id}/cursors` | Its cursors — per-iterable walk state, the real sync-progress view |
| `PATCH /api/knowledge/{id}` | Edit a knowledge — see [`knowledge-edit-design.md`](./knowledge-edit-design.md) |
| `POST /api/knowledge/{id}/pause` / `.../resume` | Pause or resume scheduling |
| `DELETE /api/knowledge/{id}` | Soft-delete + tear down chunks, entities, cursors |
| `GET /api/connections` / `GET /api/connections/{id}` | List / fetch reusable credentials |
| `POST /api/connections` | Create a connection (verified against the source) |
| `PATCH /api/connections/{id}` / `DELETE /api/connections/{id}` | Edit / remove a connection |
| `POST /api/connections/{id}/default` | Make it the default connection for its `SourceType` |
| `POST /api/index/knowledge/{id}/sync` | Re-arm forward cursors now (incremental trigger) |
| `POST /api/index/knowledge/{id}/retry-failed` | Revive dead-lettered work: `FAILED` cursors → `AVAILABLE`, `FAILED` entities → `INGESTED`, both with a fresh retry budget. The only exit from `FAILED`; distinct from `/sync`, which re-arms forward cursors only and so can never reach a backward one |
| `POST /api/index/knowledge/{id}/reindex` | Re-index every entity of a knowledge — the opt-in path after a chunking or embedding-model change. Returns `{knowledgeId, queued, refetching, cursorsReset}`; the last two are non-zero only for a connector whose content is a staged copy (see below) |
| `POST /api/index/entities/{id}/reindex` | Re-index one entity, re-fetching its content first if the connector stages a copy. That fetch is synchronous, so the call can take as long as one download |
| `DELETE /api/index/entities/{id}` | Tombstone an entity (chunks removed by the indexing stage) |
| `POST /api/search` | Hybrid / lexical / semantic search |
| `GET /q/health` | SmallRye health / readiness |

`personal-search-assistant.postman_collection.json` at the repo root exercises all of these.

### Re-index: when it re-fetches, and how (L11)

"Re-index" means *make this current again*, and the caller never says how. Whether the stored content
can be trusted is a property of the connector, not of the request: inline text lives in Mongo and a
`LOCAL_FS` `fileRef` names the user's own file, but Drive's `fileRef` is a copy in a scratch dir the
OS may empty. Each connector declares this as a `ReindexMode`, and `app.indexing.refetch-on-reindex`
(`auto` / `always` / `never`) overrides it.

| | one entity | one knowledge |
|---|---|---|
| Route | `SourceConnector.fetchOne` → `materialize` → `upsert`, synchronously | flag `Entity.needsRefetch` on every file-backed entity, then rewind the cursors |
| Why | a walk pages through a source and cannot be asked for one known id | the ingestion walk already has the leases, permits and rate limiting that thousands of downloads from an HTTP thread would not |
| Fetches | exactly one | one per changed-or-flagged item, spread across ticks |

`needsRefetch` is the one escape from the checksum skip (invariant 3) — "unchanged at the source" is
precisely the case it exists to override — and `upsert` clears it in the same write that stores the
fresh bytes. The rewind matters just as much: the forward cursor's high-water floor is what stops an
unmodified file from ever being offered again. Flag before rewind, never after, or a cursor that
starts walking first re-skips those items. `RETIRED` cursors stay parked, and a `BACKWARD` cursor is
rewound only when backfill is still enabled or it has already `EXHAUSTED`.

A missing staged file is also the one indexing failure that is terminal on sight: `IndexingRunner`
separates `NoSuchFileException` from an unreadable-but-present file, dead-letters it immediately
(no backoff can make the file reappear) and sets `needsRefetch` in the same fenced write, so the next
walk that re-lists the item repairs it.

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
| `app.ingestion.retry-limit` | `5` | **Consecutive** cursor failures before `FAILED`; a successful run resets the streak |
| `app.ingestion.permits.global` / `.connector` / `.knowledge` | `8` / `4` / `2` | Scoped ingestion concurrency ceilings |
| `app.ingestion.permits.ttl-seconds` | `14400` | Ingestion permit TTL; renewed per page like the lease, so keep `>= app.ingestion.lease-seconds` |
| `app.scheduler.forward-interval` | `1m` | Scheduler **tick** granularity — how often it checks which knowledges are due. Bounds the finest schedule, not how often any one source syncs |
| `app.scheduler.default-interval` | `1d` | Global-default forward-sync interval (last resort: used when a knowledge has no custom schedule and its connector declares no `defaultSchedule()`) |
| `app.scheduler.default-cron` | _(empty)_ | Optional global-default cron; if set, wins over `default-interval`. Cron beats interval at every tier |
| `app.scheduler.discovery-interval` | `60m` | How often dynamic-iterable sources are re-discovered (new folders/channels) |
| `app.indexing.poll-interval` | `5s` | Indexing loop tick |
| `app.indexing.batch` | `20` | Global budget of entities indexed per tick |
| `app.indexing.per-knowledge` | `5` | Per-knowledge claim quota per tick (round-robin fairness) |
| `app.indexing.max-knowledges` | `200` | Cap on distinct knowledges scanned per indexing tick |
| `app.indexing.concurrency` | `4` | Global indexing concurrency ceiling |
| `app.indexing.permits.ttl-seconds` | `1200` | Indexing permit TTL; held for a whole tick (not renewed mid-tick), so size above the worst-case tick time |
| `app.indexing.embed-batch` | `15` | Chunks per embedding call |
| `app.indexing.lease-seconds` | `900` | Entity indexing lease duration |
| `app.indexing.retry-limit` | `5` | **Consecutive** indexing failures before `FAILED`; a successful index resets the streak |
| `app.indexing.backoff-seconds` | `300` | Delay before a failed entity is re-claimable |
| `app.indexing.refetch-on-reindex` | `auto` | Whether a re-index fetches content from the source first. `auto` asks the connector (`GOOGLE_DRIVE` fetches, because its `fileRef` is a staged copy; everything else re-indexes what is stored); `always` forces it for every file-backed entity; `never` restores the old behaviour of always trusting the stored reference, which makes a purged staged file dead-letter loudly instead of silently re-downloading. See §5 |
| `app.chunking.strategy` | `recursive` | Default chunking strategy when a knowledge hasn't set one (`recursive`/`character`/`fixed-size`/`token`/`table`). See [`parsing-and-chunking.md`](./parsing-and-chunking.md) |
| `app.chunking.mime-aware` | `true` | Allow content type to pick the strategy when the knowledge has not chosen one explicitly (`ChunkingStrategy.prefers`). An explicit per-knowledge choice always wins. Off restores name-only selection |
| `app.chunking.size` / `.overlap` | `1000` / `150` | Character size + overlap for the character-based strategies |
| `app.chunking.token.size` / `.overlap` | `256` / `32` | Token size + overlap for the `token` strategy |
| `app.chunking.token.tokenizer` | `bert-base-uncased` | HuggingFace tokenizer id used by the `token` strategy (lazy load, ~4-chars/token fallback) |
| `app.embedding.provider` | `openai-embed` | Which `EmbeddingProvider` is active, matched against each provider's `providerId()`: `openai-embed` (hosted) / `onnx-bge` (local in-JVM ONNX) / `local-hashing` (offline dev baseline). See [`providers.md`](./providers.md) |
| `app.embedding.dimension` | `768` | Vector width. **Baked into the `knn_vector` mapping** when `chunks_v3_768` is created — changing to a different-width model needs a new physical index + alias flip + full re-index. Deliberately has **no code default** at any injection point: a guessed width silently builds an index nothing fits, so an absent property fails startup instead |
| `app.embedding.onnx.model` / `.model-path` | `bge-base-en-v1.5` / _(empty)_ | Local ONNX model id and the directory holding `model.onnx` + `tokenizer.json` + `config.json`. **Ships empty**, so `onnx-bge` throws until you export a model — see [`providers.md`](./providers.md) for the one-line export |
| `app.embedding.onnx.pooling` / `.normalize` | `cls` / `true` | Pooling strategy and L2 normalization for the ONNX provider |
| `app.embedding.openai.base-url` / `.model` / `.api-key` | Gemini OpenAI-compatible endpoint / `models/gemini-embedding-001` / `${GEMINI_API_KEY:}` | Hosted embedding provider (`openai-embed`). Gemini needs the `models/` prefix; a bare id 404s |
| `app.embedding.openai.dimensions` | `768` | Width requested via the OpenAI `dimensions` parameter; `0` omits it and takes the model's native width. `gemini-embedding-001` is natively 3072, so this is what keeps it inside the 768 knn mapping. A model that ignores the parameter fails loudly on the first batch |
| `app.llm.provider` | `openai-compat` | `openai-compat` (hosted Groq/Gemini or local Ollama) or `none` (`StubLlmProvider`, disables `answer: true`) |
| `app.llm.base-url` / `.model` / `.api-key` | Groq / `llama-3.3-70b-versatile` / `${GROQ_API_KEY:}` | Grounded-answer LLM. Point `base-url` at `http://localhost:11434/v1` for Ollama — no code change |
| `app.search.snippet-chars` | `280` | Length of a hit's **display** excerpt. Display only: the agent is grounded in the full chunk text, not this. `0` returns chunks untruncated. Used to be a hardcoded constant applied *before* the text reached the agent — see [`opensearch-index.md`](./opensearch-index.md) |
| `app.search.highlight-fragments` | `2` | Highlight fragments requested per field, so the excerpt is the region that matched rather than the head of the chunk. `0` disables highlighting. Lexical leg only — a knn query has no query terms to mark up |
| `app.search.max-top-k` | `100` | Ceiling on `topK`, clamped in `DefaultSearchService`. `topK` is multiplied before it becomes the OpenSearch `size` and knn `k`, so this is what bounds a single request |
| `app.search.candidate-multiplier` | `4` | Candidates fetched per leg per requested result. Over-fetch is what lets a chunk only one leg ranks well reach the fusion step |
| `app.search.lexical.fields` / `.type` / `.minimum-should-match` / `.phrase-boost` | `text,title^2` / `best_fields` / `2<70%` / `2.0` | BM25 query shape. A bare `multi_match` scores a document for matching **any** term, so a conversational query ranked documents containing "give"/"all"/"this"/"year" above the one document on topic and flooded the candidate set with them. Blank `minimum-should-match` sends nothing; `0` phrase-boost omits the phrase clause |
| `app.search.rrf-k` | `60` | RRF rank-smoothing constant. Larger rewards agreement between the legs over either leg's exact ordering |
| `app.search.rrf.lexical-weight` / `.vector-weight` | `1.0` / `1.0` | Per-leg weights on the fused score, for discounting a leg you trust less on your corpus |
| `app.search.max-chunks-per-entity` | `0` (unlimited) | Cap on how many chunks one entity may contribute. **Off by default on purpose** — it buys source diversity but harms the case where the right answer *is* many chunks of one document |
| _(moved)_ `embedContext` field set | `["title"]` | Context fields prefixed to a chunk's text **before embedding** now live in `config/field-sets.json`, scoped per connector — a Gmail chunk is best identified by sender, a Drive chunk by heading path, and a flat key can say only one thing. `title`/`uri` resolve against the chunk, anything else against its metadata. **Changing it requires a re-index** |
| `app.embedding.openai.task-type-enabled` | `false` | Send `task_type` to distinguish a query embedding from a document one. **Must stay off for the shipped base-url**: it is a *native* Gemini parameter and the OpenAI-compatible endpoint rejects it with `400 … Unknown name "task_type"`, failing all indexing. The asymmetry is real but unreachable through the compat layer; the ONNX provider gets it via `app.embedding.onnx.query-instruction` |
| `app.embedding.onnx.query-instruction` | BGE's published wording | Instruction prepended to a **query** only. Blank for a symmetric model — the wrong instruction is worse than none |
| `app.agent.task` | `answer` | Which task in `config/prompts.json` answering runs. The task carries its own prompt, LLM profile and budgets, so a variant is a JSON entry plus this key — not a second code path. Budgets (`contextChars`, `maxSources`) live on the task, not here, because they are per-task: see [`configuration.md`](./configuration.md) |
| `app.prompts.path` | _(empty)_ | Optional file replacing `config/prompts.json` wholesale (replace, not merge). The seam for user-supplied prompts |
| `app.field-sets.path` | _(empty)_ | Optional file replacing `config/field-sets.json` wholesale |
| `app.llm.profile.<name>.{base-url,model,temperature,max-tokens,api-key}` | see `application.properties` | Named per-role LLM overrides on top of `app.llm.*`, resolved **dynamically** — adding a profile is config only, no code. Unset (or blank) inherits the provider default. A profile that redirects `base-url` must supply its own `api-key`; it will not inherit one. Ships `answer` and `lite`. See [`providers.md`](./providers.md) |
| `app.ingestion.google.client-id` / `.client-secret` | `${GOOGLE_OAUTH_CLIENT_ID:}` / `${GOOGLE_OAUTH_CLIENT_SECRET:}` | App-level OAuth fallback used to mint Gmail/Drive access tokens from a refresh token when the connection's auth blob carries no client of its own |
| `app.ingestion.google-drive.max-file-bytes` / `.max-folders` | `26214400` / `500` | Drive download size cap (oversized files are silently skipped — see [`limitations.md`](./limitations.md) L4) and the discovery folder-walk bound |

---

## 7. Extension points

| To add… | Implement… | Register via |
|---|---|---|
| A new data source (Slack, Drive, Gmail) | `ingestion.connector.SourceConnector` | CDI bean; auto-discovered by `CdiConnectorRegistry` keyed on `SourceType` |
| A new file type / extractor (OCR, etc.) | `indexing.parser.ContentParser` (set `priority()`; delegate to `TikaSupport`, which preserves table/heading structure) | CDI bean; selected by `CdiParserRegistry`. Per-family parsers already exist (PDF/Word/PPT/Excel/HTML) — see [`parsing-and-chunking.md`](./parsing-and-chunking.md) |
| A new chunking strategy | `indexing.chunking.ChunkingStrategy` (unique `name()`; override `chunk(…ParsedContent…)` to use document structure, and `prefers(contentType)` to claim a format) | CDI bean; discovered by `CdiChunkingStrategyRegistry`, selected per knowledge via `config.chunking` or by content type |
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
- **`grab(GrabContext) → GrabResult`** — paginate however the source works. The connector **owns its
  pagination state** via `CursorPosition`: a free-form, multi-field bag (`{"pageToken": …}`,
  `{"offset": …}`, `{"sinceMillis": …, "lastId": …}`, a change-id, an mbox byte offset, …). The core
  never interprets it — it just persists the `nextPosition` you return and hands it back on the next
  page. Build positions with `CursorPosition.builder().put(…).build()` and read them with the typed
  accessors (`getString`/`getLong`/`getInt`). `GrabContext` is an object (not a parameter list) so
  new optional inputs can be added later without breaking existing connectors. **`grab` must be
  stateless and idempotent** — the same page may be replayed after a crash, and files are passed as
  a `fileRef` path rather than bytes (Mongo's 16 MB document cap).
- **`requiresConnection()` / `verifyConnection(Connection)`** — credentialed sources return `true`
  and get their credentials from a reusable `Connection` rather than from the knowledge. See
  [`connectors.md`](./connectors.md).

Rather than implement `grab` by hand, most sources extend a ready-made base: **`TokenWindowGrabber`**
for token-paged APIs (Gmail, Drive) or **`TimeWindowGrabber`** for keyset APIs that resume by
`(timestamp, id)` with no page token.

Implement those plus `type()` and `verify()`, annotate `@ApplicationScoped`, and add the enum
constant to `SourceType`. Nothing else changes — the ingestion loop, cursors, permits, indexing and
search are all source-agnostic. `LocalFsConnector` is the simplest worked example (no auth, position
`{"lastModifiedMillis": <long>, "path": <string>}`); `GmailConnector` and `GoogleDriveConnector` are
the credentialed, token-paged ones.

---

## 8. Deferred (next increments)

These are intentionally out of scope for this build (per the design's phasing) and have their
seams already in place:

- **Webhooks.** `ForwardCursorScheduler.armNow(knowledgeId)` is the on-demand trigger a webhook
  endpoint would invoke; the webhook config already lives on `Knowledge.Config.webhookSettings`.
- **File splitting** for very large or container files (zip / mbox) — slots into
  `IndexingRunner.extract` before extraction.
- **Metrics endpoints** beyond the current counters/logging (e.g. Micrometer gauges for lag and
  queue depth, surfaced through the existing health/metrics infrastructure).
- **Redis-backed `PermitService`** for multi-node deployments. The in-memory implementation is
  single-node only.
- **A cross-encoder `Reranker`** in place of `NoopReranker`.
