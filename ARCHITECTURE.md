# Architecture — Personal Search Assistant

The structure and the contracts (interfaces) that everything else plugs into. The ingestion,
indexing, retrieval, and agent paths are all implemented; this document describes the shape,
not a plan.

## Guiding principle: Ports & Adapters (Hexagonal)

The core domain knows *nothing* about MongoDB, OpenSearch, Gmail, or any LLM. It only
knows about **ports** — interfaces it defines. Concrete technologies are **adapters**
that implement those ports. This is what makes "future integrations" cheap: adding a new
data source, a new vector store, or a new LLM means writing one new adapter, not touching
the core.

```
                        ┌──────────────────────────────────────┐
   REST clients ──────► │                 api                  │   inbound adapter
                        │        (JAX-RS resources, DTOs)      │
                        └──────────────────┬───────────────────┘
                                           │ calls
                        ┌──────────────────▼───────────────────┐
                        │                 app                  │   use cases
                        │  DefaultKnowledgeService / Indexing  │
                        │  / Search / ConnectionService        │
                        └──────────────────┬───────────────────┘
                                           │ depends on ports only
                        ┌──────────────────▼───────────────────┐
                        │               domain                 │
                        │   model (records) + service ports    │   the core
                        └───┬───────────┬──────────┬───────────┘
        defines ports ──────┘           │          └────── defines ports
                                        │
   ┌───────────────┐   ┌────────────────▼─────┐   ┌──────────────────┐
   │  ingestion    │   │      indexing        │   │    retrieval     │
   │SourceConnector│   │ ContentParser        │   │ Retriever        │
   │               │   │ ChunkingStrategy     │   │ Reranker         │
   │               │   │ EmbeddingProvider    │   │                  │
   └───────────────┘   └──────────────────────┘   └──────────────────┘
                                        │
                        ┌───────────────▼──────────────────────┐
                        │              storage                 │   outbound adapters
                        │  repository ports  ──► mongo adapter │
                        │  SearchIndex port  ──► opensearch    │
                        └──────────────────────────────────────┘
                        ┌──────────────────────────────────────┐
                        │               agent                  │
                        │   SearchAgent  +  LlmProvider port   │
                        └──────────────────────────────────────┘
```

## Vocabulary

| Term | Means |
|---|---|
| **Knowledge** | One connected, configured source instance (a folder, a mailbox, a Drive account + filters). |
| **Entity** | One ingested item within a knowledge (a file, an email, a message). |
| **Iterable** | An independently-paged sub-stream of a knowledge (a folder, a label, a channel). |
| **Cursor** | The per-`(knowledge, iterable, direction)` walk state — position, lease, retry, stats. |
| **Connection** | Reusable credentials for a `SourceType`, shared across knowledges. |
| **Chunk** | A slice of an entity's text plus its embedding. Lives only in OpenSearch. |

## Package map (`io.personalassistant`)

| Package | Role | Type |
|---|---|---|
| `api.resource` | JAX-RS endpoints: `Knowledge`, `Connection`, `Indexing`, `Search` (+ hand-rolled `PATCH`) | adapter |
| `api.dto` | Request/response DTOs, decoupled from domain | adapter |
| `app` | Use-case implementations: `DefaultKnowledgeService`, `DefaultIndexingService`, `DefaultSearchService`, `DefaultConnectionService` | core |
| `domain.model` | Core records: `Knowledge`, `Entity`, `Cursor`, `CursorPosition`, `Connection`, `DiscoveryStatus`, `Chunk`, `RawItem`, `ParsedContent`, `Embedding`, `SyncSchedule` | core |
| `domain.model.enums` | `SourceType`, `KnowledgeStatus`, `EntityStatus`, `EntityType`, `CursorStatus`, `CursorDirection`, `ConnectionStatus`, `DiscoveryOutcome`, `DiscoveryTrigger` | core |
| `domain.model.search` | `SearchQuery`, `SearchHit`, `SearchResponse` | core |
| `domain.service` | Use-case ports: `KnowledgeService`, `IndexingService`, `SearchService`, `ConnectionService`, `KnowledgePatch` | core |
| `ingestion.connector` | `SourceConnector` port + registry, `GrabContext`/`GrabResult`, `SourceIterable`, `TimeWindow`, and the `TokenWindowGrabber` / `TimeWindowGrabber` base classes | core port |
| `ingestion.connector.localfs` · `.google.gmail` · `.google.drive` | Connector adapters | adapter |
| `ingestion.job` | `IngestionJob` poll loop, `IngestionRunner`, `ForwardCursorScheduler`, `IterableDiscoveryScheduler` | core |
| `ingestion.schedule` | `ScheduleResolver` — custom → connector default → global default | core |
| `indexing.parser` | `ContentParser` port + per-format parsers and the Tika fallback | core port |
| `indexing.chunking` | `ChunkingStrategy` port, `ChunkingSpec`/`ChunkingSpecResolver`, four strategies | core port |
| `indexing.embedding` | `EmbeddingProvider` port + ONNX / hosted / hashing providers + selector | core port |
| `indexing.job` | `IndexingJob` poll loop, `IndexingRunner` | core |
| `retrieval` | `Retriever` (`HybridRetriever`, RRF fusion) + `Reranker` (`NoopReranker`) ports | core port |
| `storage.repository` | `KnowledgeRepository`, `EntityRepository`, `CursorRepository`, `ConnectionRepository`, `DiscoveryStatusRepository` ports | core port |
| `storage.mongo` | MongoDB adapters + `MongoIndexInitializer` (creates indexes on startup) | adapter |
| `storage.search` | `SearchIndex` port | core port |
| `storage.search.opensearch` | OpenSearch adapter, client producer, `OpenSearchIndexInitializer` | adapter |
| `agent` · `agent.llm` | `SearchAgent` orchestration + `LlmProvider` port and its providers | core/port |
| `common` · `.id` · `.concurrency` | `ProviderImpl` qualifier, `Durations`, `Errors`, `Ids`, `PermitService` | shared |

There is no exception-mapper package — resources map exceptions to status codes inline
(`NoSuchElementException` → 404, `IllegalArgumentException` → 400, `IllegalStateException` → 409).

## Why MongoDB *and* OpenSearch (separation of concerns)

- **MongoDB = source of truth.** Canonical records — every knowledge, every entity, every
  cursor, and the sync bookkeeping. Durable, easy to reprocess from. If we ever rebuild the
  search index, Mongo is what we replay from.
- **OpenSearch = the query engine.** A derived, disposable index optimized for retrieval:
  BM25 keyword matching **and** `knn_vector` similarity in one place, so we get **hybrid
  search** without a separate vector DB. It can always be rebuilt from Mongo.

This split means the two never fight over responsibilities: Mongo owns *truth and
lifecycle*, OpenSearch owns *fast relevance*.

## Core data flow

The write path is **two independent stages**, decoupled through Mongo. Each is its own poll
loop, so a slow parse can never stall fetching and a rate-limited API can never stall indexing.

**Stage 1 — ingestion (source → Mongo).** `IngestionJob` ticks every `app.ingestion.poll-interval`.

1. Claim a lease on a claimable `Cursor` (scoped concurrency permits bound the fan-out).
2. `SourceConnector.grab(GrabContext)` returns one page of `RawItem`s plus the next
   `CursorPosition` — the connector owns its own pagination state.
3. Upsert each item as an `Entity` keyed by `(knowledgeId, externalId)`. An unchanged
   `checksum` on an already-`INDEXED` entity is skipped outright; otherwise it lands `PENDING`.
4. Persist the advanced cursor position under the lease and repeat, or release.

**Stage 2 — indexing (Mongo → OpenSearch).** `IndexingJob` ticks every `app.indexing.poll-interval`,
claiming entities round-robin across knowledges so one backlog can't starve the rest.

1. `ContentParser` extracts `ParsedContent` (text + metadata) from the entity's inline text or `fileRef`.
2. `ChunkingStrategy`, resolved per knowledge by `ChunkingSpecResolver`, splits it into `Chunk`s.
3. `EmbeddingProvider` embeds them in batches of `app.indexing.embed-batch`.
4. `SearchIndex.deleteByEntity(id)` then `indexChunks(...)` — an idempotent replace, with chunk
   id `entityId_ordinal`.

**Search (read path)**

1. REST query → `SearchService`.
2. `EmbeddingProvider` embeds the query (skipped for `LEXICAL` mode).
3. `HybridRetriever` runs BM25 and k-NN against `SearchIndex` and fuses them with RRF.
4. `Reranker` reorders the candidates (currently a no-op passthrough).
5. `SearchAgent` (when `answer: true`) calls `LlmProvider` to synthesize a grounded, cited answer.
6. Response returned with source attribution.

## Extension points (where future integrations plug in)

| To add… | Implement… | No change needed in… |
|---|---|---|
| A new data source (Slack, Notion…) | `SourceConnector` + a `SourceType` constant | domain, storage, retrieval |
| A new file type | `ContentParser` | everything else |
| A different embedding model | `EmbeddingProvider` | everything else |
| A different vector store / DB | `SearchIndex` or repository ports | domain, api |
| A different LLM | `LlmProvider` | everything else |
| A new chunking approach | `ChunkingStrategy` | everything else |
| Multi-node concurrency | `PermitService` (Redis-backed) | everything else |

Adapters are **plain `@ApplicationScoped` CDI beans** — there is no central registration list to
edit. `CdiConnectorRegistry`, `CdiParserRegistry`, and `CdiChunkingStrategyRegistry` discover them
and select by `SourceType` / MIME type + `priority()` / strategy name. Embedding and LLM providers
are the exception: they carry the `@ProviderImpl` qualifier and the *active* one is produced by
`EmbeddingProviderSelector` / `LlmProviderSelector` from config, so callers inject the port and
never a concrete provider.

## Conventions

- **Java 21 records** for the immutable domain model and all DTOs.
- **Interfaces in core packages, implementations in adapter sub-packages** (e.g.
  `storage.repository` port → `storage.mongo` impl).
- DTOs never leak into the domain; mapping happens at the `api` boundary.
- IDs are opaque prefixed strings minted by `Ids` (`kn_`, `ent_`, `conn_`, `cur_`, `dsc_`). Cursor,
  discovery, and chunk ids are *derived* from their parents so re-runs are idempotent rather than
  duplicating.

See `docs/knowledge-lifecycle.md` and `docs/indexing-implementation.md` for the authoritative
behavioural detail, and `docs/mongodb-schema.md` / `docs/opensearch-index.md` for persistence.
