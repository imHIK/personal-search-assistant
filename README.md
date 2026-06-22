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

## REST endpoints (skeleton)
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/search` | Search; optional grounded answer |
| GET  | `/api/sources` | List configured sources |
| POST | `/api/sources` | Register a source |
| DELETE | `/api/sources/{id}` | Remove a source |
| POST | `/api/index/sources/{id}/sync` | Trigger incremental sync |
| POST | `/api/index/documents/{id}/reindex` | Re-index one document |

## Status
Architecture + full Quarkus wiring is in place and boots. The storage adapters
(Mongo/OpenSearch) and the embedding/LLM providers are stubs — implemented next, one
vertical slice at a time (starting with the local-filesystem connector).
