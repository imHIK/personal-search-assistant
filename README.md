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

> First time: generate the Gradle wrapper with `gradle wrapper` (or use your IDE's
> Quarkus run config) if `./gradlew` is not present yet.

## REST API

All endpoints consume/produce `application/json`. Base URL: `http://localhost:8080`.

### Knowledge — connected sources (`/api/knowledge`)
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/knowledge` | List all knowledge sources |
| GET | `/api/knowledge/{id}` | Get a single knowledge source |
| POST | `/api/knowledge` | Register, validate and activate a knowledge source |
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
Architecture + full Quarkus wiring is in place and boots. The storage adapters
(Mongo/OpenSearch) and the embedding/LLM providers are stubs — implemented next, one
vertical slice at a time (starting with the local-filesystem connector).
