# Personal Search Assistant

Indexes your local/cloud data (files, messages, metadata) and runs intelligent search
over it. Built on Java 21 + Quarkus, MongoDB (source of truth) and OpenSearch (hybrid
keyword + vector query engine).

See **ARCHITECTURE.md** for the design, and `docs/` for the MongoDB schema and OpenSearch
index mapping.

A React web console ships with it — search, source management, indexing progress and per-item
controls — served by the same Quarkus process on :8080. See **Web console** below.

## Prerequisites
- JDK 21+
- Docker (for MongoDB + OpenSearch)
- Node 20+ and npm (for the web console; `./gradlew build` invokes them)

## Run locally

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Run the app in dev mode (live reload)
./gradlew quarkusDev
```

App + console: http://localhost:8080 · Health: http://localhost:8080/q/health

> `quarkusDev` serves whatever version of the console was last built into
> `src/main/resources/META-INF/resources`. When working on the frontend, run the Vite dev server
> alongside it (`./gradlew frontendDev`) and use **http://localhost:5173** instead — it hot-reloads
> and proxies `/api` and `/q` to :8080, so it is still a single origin and needs no CORS config.

## Web console

| Screen | What it covers |
|---|---|
| Search (`/`) | Query, match style, scope, grounded answers with clickable `[n]` citations |
| Sources (`/knowledge`) | Add / pause / resume / remove, check now, live indexing progress |
| Source detail | Overview · Items (paged, per-item reprocess/remove) · Sync activity · Settings |
| Accounts (`/connections`) | Google credentials, default per type, masked secrets |

Two things worth knowing about how it presents the system:

- **It speaks user language, not domain language.** The five status enums, cursor directions and
  positions are collapsed into a small vocabulary ("Up to date", "Importing older items",
  "Searchable", "Couldn't process"). The **Technical details** toggle in the top bar reveals every
  raw id, enum name, checksum, cursor position and score in place — off by default, persisted.
- **Connectors are data, not code.** `frontend/src/config/connectors.ts` holds one descriptor per
  `SourceType` (label, icon, whether it needs an account, its input/auth/config fields). The wizard,
  forms, filters and badges all render from it, so adding a connector to the UI is appending an
  object and flipping `implemented: true` — no component changes. The same pattern covers status
  presentation (`presentation.ts`), copy (`labels.ts`), error translation (`errors.ts`) and
  not-yet-built capabilities (`features.ts`).

```bash
./gradlew frontendDev     # Vite dev server on :5173 with hot reload
./gradlew frontendBuild   # build the console into META-INF/resources (runs as part of `build`)
```

> **Embeddings.** The default provider (`app.embedding.provider=onnx-bge`) runs a local ONNX model
> and needs `app.embedding.onnx.model-path` pointed at an exported model directory — it ships empty,
> so embedding throws until you set it. For local dev without the model, set
> `app.embedding.provider=local-hashing`. See [`docs/providers.md`](docs/providers.md).
>
> **Optional env vars.** `GROQ_API_KEY` (grounded answers), `GEMINI_API_KEY` (hosted embeddings),
> `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` (Gmail/Drive token refresh). All genuinely
> optional — the app starts without any of them; the corresponding feature just stays off.

## REST API

All endpoints consume/produce `application/json`. Base URL: `http://localhost:8080`.
`personal-search-assistant.postman_collection.json` at the repo root exercises everything below.

### Knowledge — connected sources (`/api/knowledge`)
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/knowledge` | List all knowledge sources |
| GET | `/api/knowledge/{id}` | Get a single knowledge source |
| GET | `/api/knowledge/{id}/entities` | Page its entities, newest-first (`status`, `limit`, `offset`) |
| GET | `/api/knowledge/{id}/cursors` | Its ingestion cursors — the real sync-progress view |
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

`GET /api/knowledge/{id}/entities` pages the ingested items newest-first (`updatedAt` descending).
`status` filters by `EntityStatus` (`INGESTED`/`INDEXING`/`INDEXED`/`FAILED`/`DELETED`, omit for
all), `limit` defaults to 50 and is clamped to 200, `offset` defaults to 0. `total` counts every
match, not the page. Rows are projections — `raw` and the extracted body are never sent.
```json
{ "items": [ { "id": "ent_…", "externalId": "", "entityType": "FILE", "status": "INDEXED",
               "title": "", "uri": "", "checksum": "", "chunkCount": 0, "embeddingModel": "",
               "indexedAt": null, "error": null, "retryCount": 0, "needsReindex": false,
               "updatedAt": "…" } ],
  "total": 0, "limit": 50, "offset": 0 }
```

`GET /api/knowledge/{id}/cursors` returns one entry per `(iterableId, direction)` — the only honest
answer to "has this finished importing?". A `BACKWARD` cursor at `EXHAUSTED` means the backfill is
complete; a `FORWARD` cursor at `IDLE` is waiting for its next scheduled run. The connector-internal
`attributes` and the worker `lease` are deliberately not exposed.
```json
[ { "id": "cur_…", "iterableId": "", "direction": "BACKWARD", "status": "EXHAUSTED",
    "retryCount": 0, "lastError": null, "lastRunAt": "…", "fetched": 1240, "position": {} } ]
```

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
{ "hits": [ { "chunkId": "", "entityId": "", "knowledgeId": "", "title": "", "snippet": "",
              "uri": "", "score": 0.0, "metadata": {} } ],
  "answer": null, "tookMs": 0 }
```
`chunkId` (`<entityId>_<ordinal>`) pins the matched passage; `knowledgeId` is what lets a caller
attribute a hit to the source it came from. When `answer` is requested, the synthesized text cites
hits as `[n]`, 1-based into `hits`.

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
