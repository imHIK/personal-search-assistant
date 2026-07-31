# Personal Search Assistant

Indexes your local/cloud data (files, messages, metadata) and runs intelligent search
over it. Built on Java 21 + Quarkus, MongoDB (source of truth) and OpenSearch (hybrid
keyword + vector query engine).

See **ARCHITECTURE.md** for the design, and `docs/` for the MongoDB schema and OpenSearch
index mapping.

## Prerequisites
- JDK 21+
- Docker (for MongoDB + OpenSearch)

## Run locally

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Run the app in dev mode (live reload)
./gradlew quarkusDev
```

App: http://localhost:8080 · Health: http://localhost:8080/q/health

> **Embeddings.** The default provider (`app.embedding.provider=onnx-bge`) runs a local ONNX model
> and needs `app.embedding.onnx.model-path` pointed at an exported model directory — it ships empty,
> so embedding throws until you set it. For local dev without the model, set
> `app.embedding.provider=local-hashing`. See [`docs/providers.md`](docs/providers.md).
>
> **Optional env vars.** `GROQ_API_KEY` (grounded answers), `GEMINI_API_KEY` (hosted embeddings),
> `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` (Gmail/Drive token refresh).

## REST API

All endpoints consume/produce `application/json`. Base URL: `http://localhost:8080`.
`personal-search-assistant.postman_collection.json` at the repo root exercises everything below.

### Knowledge — connected sources (`/api/knowledge`)
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/knowledge` | List all knowledge sources |
| GET | `/api/knowledge/{id}` | Get a single knowledge source |
| POST | `/api/knowledge` | Register, validate and activate a knowledge source |
| PATCH | `/api/knowledge/{id}` | Edit name, schedule, inputs, auth, backfill or chunking |
| POST | `/api/knowledge/{id}/pause` | Pause forward scheduling/ingestion |
| POST | `/api/knowledge/{id}/resume` | Resume a paused knowledge source |
| DELETE | `/api/knowledge/{id}` | Remove a knowledge source |

`POST /api/knowledge` body:
```json
{
  "name": "My Documents",
  "type": "LOCAL_FS",
  "auth": {},
  "inputs": { "rootPath": "/home/me/Documents" },
  "cron": null,
  "scheduleEnabled": true,
  "backfillEnabled": true
}
```
`type` is one of `LOCAL_FS`, `GMAIL`, `SLACK`, `GOOGLE_DRIVE`, `NOTION`. `cron`,
`scheduleEnabled` and `backfillEnabled` are optional — server-side defaults are applied.

Chunking is optional and customisable per knowledge (on this `POST`, or later via `PATCH
/api/knowledge/{id}` as a direct update — new chunks use the new strategy, existing chunks are kept):
`chunkingStrategy` (`recursive`/`character`/`fixed-size`/`token`), `chunkingMaxSize`,
`chunkingOverlap`, `chunkingSeparators`. Any omitted field inherits the global default. Files are
extracted per type (PDF, Word, PowerPoint, Excel, HTML, text) for cleaner text. See
[`docs/parsing-and-chunking.md`](docs/parsing-and-chunking.md).

`connectorDetails.type` is immutable after creation, and a deleted knowledge cannot be edited. See
[`docs/knowledge-edit-design.md`](docs/knowledge-edit-design.md) for which edits trigger a
re-verify/re-discover cycle versus a plain in-place write.

### Connections — reusable credentials (`/api/connections`)
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/connections` | List connections (optionally filtered by type) |
| GET | `/api/connections/{id}` | Get a single connection |
| POST | `/api/connections` | Create and verify a connection |
| PATCH | `/api/connections/{id}` | Update name, auth or config |
| POST | `/api/connections/{id}/default` | Make it the default for its `SourceType` |
| DELETE | `/api/connections/{id}` | Remove a connection |

Credentialed sources (Gmail, Drive) authenticate through a `Connection` rather than through the
knowledge, so re-authenticating an account doesn't mean editing every knowledge that uses it. A
knowledge either names a `connectionId` or falls back to the default for its type. See
[`docs/connectors.md`](docs/connectors.md).

### Search (`/api/search`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/search` | Hybrid search; optional grounded answer |

Request body (only `query` is required):
```json
{
  "query": "quarterly revenue",
  "knowledgeIds": ["kn_123"],
  "filters": { "sourceType": "EMAIL" },
  "topK": 10,
  "mode": "HYBRID",
  "answer": false
}
```
`mode` is `LEXICAL`, `SEMANTIC` or `HYBRID` (default `HYBRID`). Response:
```json
{ "hits": [ { "entityId": "", "title": "", "snippet": "", "uri": "", "score": 0.0, "metadata": {} } ],
  "answer": null, "tookMs": 0 }
```

### Indexing (`/api/index`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/index/knowledge/{id}/sync` | Trigger a forward sync; returns `{ knowledgeId, cursorsArmed }` |
| POST | `/api/index/entities/{id}/reindex` | Re-index one entity (no re-fetch) |
| DELETE | `/api/index/entities/{id}` | Remove an entity (its chunks are deleted) |

### Health
| Method | Path | Purpose |
|---|---|---|
| GET | `/q/health` | Liveness/readiness probes |

## Status

The full pipeline is implemented end to end: ingestion (cursor scheduling, leasing, backfill +
incremental sync, concurrency permits), indexing (per-format parsing, four chunking strategies,
embedding, OpenSearch writes), hybrid retrieval with RRF fusion, and grounded answers.

Three connectors ship — `LOCAL_FS`, `GMAIL`, `GOOGLE_DRIVE`; `SLACK` and `NOTION` are enum values
with no adapter yet. Embeddings run locally via ONNX or against a hosted API; the LLM is any
OpenAI-compatible endpoint (Groq by default, or a local Ollama).

Known gaps are tracked in [`docs/limitations.md`](docs/limitations.md) and prioritized in
[`ROADMAP.md`](ROADMAP.md) — the notable ones are no reranker, no auth, no evaluation harness, and
no OCR.
