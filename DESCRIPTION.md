# Personal Search Assistant

## Overview
A personal search system that indexes data from local and cloud sources, then runs
intelligent (agentic) search across all of it to return the best possible answer.
Data can be **messages, files, or metadata**. Answers are grounded in the user's own
data and cite their sources.

---

## Architecture Layers

### 1. Data Ingestion (Connectors)
Pulls raw data from sources into the system.

- **Source connectors**: local filesystem, Gmail, Slack, Google Drive, Notion (start
  with 1–2, design the interface to be pluggable).
- **Parsing & extraction**: extract text from PDF, DOCX, HTML, images (OCR), and email.
- **Normalization**: convert every source into a common internal document model
  (content + metadata + source reference).
- **Incremental sync**: change detection, deduplication, handling deletes, and scheduled
  re-indexing — avoid full re-index on every run.

### 2. Indexing Pipeline
Turns normalized documents into searchable form.

- **Chunking**: split documents into chunks (size + overlap), carrying metadata per chunk.
- **Embedding generation**: vector embeddings per chunk.
- **Async processing**: a queue/broker (Kafka or RabbitMQ) connecting ingestion →
  parsing → embedding → storage, so indexing is decoupled and retryable.
- **Storage fan-out**: write objects to the primary DB, embeddings to the vector store,
  and hot lookups to the KV cache.

### 3. Storage
- **Primary object store**: MongoDB — documents, metadata, source records, sync state.
- **Vector store**: OpenSearch / Elasticsearch (chosen deliberately for **hybrid
  search**) or pgvector. Stores embeddings + supports keyword search in one engine.
- **Key-value cache**: for fast, frequent lookups (e.g. Redis) — optional, add when
  hot paths are identified.
- **Data model / schema**: defined object schema for Mongo and chunk-metadata schema
  for the vector store.

### 4. Retrieval
Finds the most relevant chunks for a query.

- **Hybrid search**: combine vector (semantic) + BM25 (keyword) retrieval.
- **Reranking**: a rerank step (cross-encoder) over the candidate set to improve
  ordering before passing to the LLM.
- **Access filtering**: restrict results to what the user is allowed to see, per source.

### 5. Agent / Answering
- **Search agent**: plans the query, decides which sources/tools to use, retrieves,
  and synthesizes an answer (consider LangChain4j on Quarkus rather than hand-rolling).
- **Grounding & citations**: every answer cites the source document/message it came from.
- **Conversation memory**: maintain context across multi-turn searches.

### 6. API
- **REST architecture**: endpoints for query, indexing control, source management, status.
- **API gateway / rate limiting** as the system grows.

---

## Cross-Cutting Concerns

- **Auth & access control**: authentication + per-source permission model (critical for
  private personal data).
- **Security**: encryption at rest, secrets management, PII handling.
- **Observability**: structured logging, metrics, tracing.
- **Evaluation**: a framework to measure search quality (recall/relevance) so changes
  can be validated.
- **Config & secrets management**.
- **Testing & CI/CD**.

---

## Tech Stack

- **Language / framework**: Java + Quarkus + Gradle.
- **Primary DB**: MongoDB.
- **Vector / search DB**: OpenSearch / Elasticsearch (or Postgres + pgvector to reduce
  the number of moving stores).
- **Cache**: Redis (if needed).
- **Embeddings**: free embedding API — *or* local model (sentence-transformers / ONNX)
  for privacy, since this is personal data.
- **LLM**: free REST LLM API — *or* local LLM (Ollama) for privacy.
- **Agent/RAG**: LangChain4j (first-class Quarkus extension).
- **Messaging**: Kafka or RabbitMQ for the async indexing pipeline.
- **Deployment**: Docker + Kubernetes.

---

## Key Decisions To Make Early

1. **Privacy vs convenience**: free hosted embedding/LLM APIs (easy) vs local models
   (private). Personal data leans toward local.
2. **Store consolidation**: Mongo + vector DB + KV is three systems. Postgres + pgvector
   could collapse this — evaluate before committing.
3. **First connector**: which single source to build end-to-end first (recommend local
   filesystem — no auth/API complexity) to prove the full pipeline.
