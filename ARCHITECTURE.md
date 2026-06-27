# Architecture — Personal Search Assistant

This pass establishes the **structure and the contracts** (interfaces). Implementations
are stubs for now; we fill them in layer by layer.

## Guiding principle: Ports & Adapters (Hexagonal)

The core domain knows *nothing* about MongoDB, OpenSearch, Gmail, or any LLM. It only
knows about **ports** — interfaces it defines. Concrete technologies are **adapters**
that implement those ports. This is what makes "future integrations" cheap: adding a new
data source, a new vector store, or a new LLM means writing one new adapter, not touching
the core.

```
                        ┌──────────────────────────────────────┐
   REST clients ──────► │                 api                  │   inbound adapter
                        │        (JAX-RS resources, DTOs)       │
                        └──────────────────┬───────────────────┘
                                           │ calls
                        ┌──────────────────▼───────────────────┐
                        │               domain                 │
                        │   model (records) + service ports    │   the core
                        │  IndexingService / SearchService     │
                        └───┬───────────┬──────────┬───────────┘
        defines ports ──────┘           │          └────── defines ports
                                        │
   ┌───────────────┐   ┌────────────────▼─────┐   ┌──────────────────┐
   │  ingestion    │   │      indexing        │   │    retrieval     │
   │ SourceConnector│  │ ChunkingStrategy     │   │ Retriever        │
   │ ContentParser │   │ EmbeddingProvider    │   │ Reranker         │
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

## Package map (`io.personalassistant`)

| Package | Role | Type |
|---|---|---|
| `api.resource` | JAX-RS REST endpoints (inbound adapter) | adapter |
| `api.dto` | Request/response DTOs, decoupled from domain | adapter |
| `api.exception` | Exception → HTTP mappers | adapter |
| `domain.model` | Core records: `Source`, `Document`, `Chunk`, search types | core |
| `domain.service` | Use-case ports: `IndexingService`, `SearchService` | core |
| `ingestion.connector` | `SourceConnector` port + registry (the source extension point) | core port |
| `indexing.parser` | `ContentParser` port (PDF/DOCX/HTML/… extraction) | core port |
| `indexing.chunking` | `ChunkingStrategy` port | core port |
| `indexing.embedding` | `EmbeddingProvider` port (hosted or local model) | core port |
| `retrieval` | `Retriever` + `Reranker` ports | core port |
| `storage.repository` | `DocumentRepository`, `SourceRepository`, `ChunkRepository` ports | core port |
| `storage.mongo` | MongoDB adapters implementing the repository ports | adapter |
| `storage.search` | `SearchIndex` port | core port |
| `storage.search.opensearch` | OpenSearch adapter implementing `SearchIndex` | adapter |
| `agent` | `SearchAgent` orchestration + `LlmProvider` port | core/port |
| `common` | config, shared exceptions, ids, utils | shared |

## Why MongoDB *and* OpenSearch (separation of concerns)

- **MongoDB = source of truth.** Canonical records — every source, every document, every
  chunk, and the sync bookkeeping. Durable, easy to reprocess from. If we ever rebuild the
  search index, Mongo is what we replay from.
- **OpenSearch = the query engine.** A derived, disposable index optimized for retrieval:
  BM25 keyword matching **and** `knn_vector` similarity in one place, so we get **hybrid
  search** without a separate vector DB. It can always be rebuilt from Mongo.

This split means the two never fight over responsibilities: Mongo owns *truth and
lifecycle*, OpenSearch owns *fast relevance*.

## Core data flow

**Indexing (write path)**
1. `SourceConnector` pulls raw items from a source (files, messages…).
2. `ContentParser` extracts plain text + metadata.
3. Persist canonical `Document` to Mongo (`DocumentRepository`).
4. `ChunkingStrategy` splits the document into `Chunk`s.
5. `EmbeddingProvider` produces a vector per chunk.
6. Chunks persisted to Mongo (`ChunkRepository`) and indexed into OpenSearch (`SearchIndex`).
7. Sync state updated so the next run is incremental.

> The steps are sequenced through `IndexingService` for now. Later this becomes an async
> pipeline behind a queue (Kafka/RabbitMQ) — but the **ports don't change**, so that swap
> is internal.

**Search (read path)**
1. REST query → `SearchService`.
2. `EmbeddingProvider` embeds the query.
3. `Retriever` runs hybrid search against `SearchIndex` (BM25 + vector).
4. `Reranker` reorders the candidates.
5. `SearchAgent` (optional) calls `LlmProvider` to synthesize a grounded, cited answer.
6. Response returned with source attribution.

## Extension points (where future integrations plug in)

| To add… | Implement… | No change needed in… |
|---|---|---|
| A new data source (Slack, Drive…) | `SourceConnector` | domain, storage, retrieval |
| A new file type | `ContentParser` | everything else |
| A different embedding model | `EmbeddingProvider` | everything else |
| A different vector store / DB | `SearchIndex` or repository ports | domain, api |
| A different LLM | `LlmProvider` | everything else |
| A new chunking approach | `ChunkingStrategy` | everything else |

Each port is discovered via a small registry (e.g. `ConnectorRegistry`) so new adapters
register themselves and are selected at runtime by type — no `switch` statements to edit.

## Conventions

- **Java 21 records** for the immutable domain model.
- **Interfaces in core packages, implementations in adapter sub-packages** (e.g.
  `storage.repository` port → `storage.mongo` impl).
- DTOs never leak into the domain; mapping happens at the `api` boundary.
- IDs are opaque strings (`DocumentId`, `ChunkId`) to avoid leaking store specifics.

See `docs/mongodb-schema.md` and `docs/opensearch-index.md` for the persistence designs.
