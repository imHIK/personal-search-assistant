# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Stack

Java 21 + Quarkus 3.33.2 (LTS), Gradle 9 (Groovy DSL), single module, root package `io.personalassistant`.
MongoDB is the source of truth; OpenSearch holds chunks + kNN vectors and is fully regenerable.

The web console lives in `frontend/` — React 19 + TypeScript + Vite, Tailwind v4, TanStack Query,
React Router. It builds into `src/main/resources/META-INF/resources` (gitignored), so Quarkus serves
API and UI on one origin and **no CORS config exists — don't add any**.

## Commands

```bash
docker compose up -d          # Mongo :27017, OpenSearch :9200 — required, Quarkus does NOT start them
./gradlew quarkusDev          # dev mode, live reload, :8080
./gradlew build               # fast-jar in build/quarkus-app; runs spotlessCheck + test
./gradlew test
./gradlew test --tests "*IndexingRunnerTest"                      # single class
./gradlew test --tests "*IndexingRunnerTest.indexesTextEntity*"   # single method
./gradlew spotlessApply       # fix import order / unused imports / trailing whitespace

./gradlew frontendDev         # Vite dev server :5173, hot reload, proxies /api + /q to :8080
./gradlew frontendBuild       # console -> src/main/resources/META-INF/resources (runs inside `build`)
cd frontend && npm run lint   # eslint; `npx tsc -b` for a type-check without bundling
```

Frontend work means running **both** `./gradlew quarkusDev` and `./gradlew frontendDev`, and using
:5173. Plain `quarkusDev` on :8080 serves the last built bundle, not your edits.

`quarkus.compose.devservices.enabled=false` is deliberate — infra ownership is docker-compose's, not Quarkus's.
Config cache is on (`gradle.properties`), so `build.gradle` edits invalidate it; that's expected noise.

Spotless is hygiene-only (imports + whitespace), **not** a reformatter — see the comment in `build.gradle`.
There is no CI. Match surrounding layout by hand; never bulk-reformat a file you're editing.

## Architecture rules

Hexagonal: `api.resource` → `app` → `domain` (ports) → adapters (`storage`, `ingestion`, `indexing`, `agent`).

- **Use-case logic goes in `app/Default*Service`.** Resources only map DTO↔domain and exception↔status
  (`NoSuchElementException`→404, `IllegalArgumentException`→400, `IllegalStateException`→409) — there is no
  exception-mapper package.
- **Nothing is registered in a central place.** Connectors, parsers, and chunking strategies are plain
  `@ApplicationScoped` beans discovered by `CdiConnectorRegistry` / `CdiParserRegistry` /
  `CdiChunkingStrategyRegistry`. Adding one = add the bean (+ a `SourceType` constant for a connector).
- **Never inject a concrete embedding or LLM provider.** They carry the `@ProviderImpl` qualifier
  (`common/ProviderImpl.java`), and the active one is produced by `EmbeddingProviderSelector` /
  `LlmProviderSelector` from `app.embedding.provider` / `app.llm.provider`.
- `PATCH` is a hand-rolled annotation (`api/resource/PATCH.java`) — import it, don't recreate it.
- Mongo indexes are created at startup in `MongoIndexInitializer` (`@Observes StartupEvent`). There is no
  migration framework — a new query pattern means a new index there. The unique `(knowledgeId, externalId)`
  index on `entities` is what makes upsert dedupe work.

### Frontend rules (`frontend/`)

- **The console is a pure REST consumer.** No domain logic; types in `src/api/types.ts` mirror the DTOs
  by hand. All fetching goes through `src/api/http.ts` (it handles the 204-no-body endpoints and the
  raw-500s the missing exception mappers produce).
- **Never branch on a `SourceType` or a status enum in a component.** Connectors are descriptors in
  `src/config/connectors.ts` and forms render from `FieldSpec` via `<SchemaForm>`; statuses go through
  the lookup tables in `src/config/presentation.ts`, which have a safe fallback so a new backend enum
  constant degrades to a neutral badge instead of crashing. Adding a connector = one object + fields.
- **Copy lives in `src/config/labels.ts`, colours only in CSS variables** (`src/index.css`). No inline
  user-facing strings, no hex literals in components.
- **The UI hides the domain machinery by default.** Ids, checksums, cursor positions, raw enum names,
  `anchor`, `syncGeneration` and RRF scores render only inside `<Technical>` / `<TechnicalPanel>`,
  behind the persisted top-bar toggle. When adding a field, decide which side of that line it is on.
- **Two async traps the API sets** — both already handled, don't undo them: `POST /api/knowledge`
  returns **200 with `status: "ERROR"`** on a failed activation (check the body, not the HTTP status),
  and `POST /api/search` with `answer: true` **500s and loses the hits** when the LLM is unavailable
  (`useSearch` retries once without the flag).
- Things the backend lacks are flags in `src/config/features.ts`, not deletions.

## Invariants — breaking these corrupts data

1. **Anchor.** Every knowledge gets `anchor = now` at creation and it never moves, including across edits.
   Forward grabs return items `>= anchor`, backward grabs `< anchor`. Violations create gaps or duplicates.
2. **Cursor lease fencing.** `advancePosition` / `release` / `recordFailure` are compare-and-set on
   `lease.owner` + not-expired. If one returns `false` the worker lost its lease and must stop touching the
   cursor immediately.
3. **`checksum` is the only change signal.** A connector must make it change whenever the item changes
   (`LOCAL_FS`: `size:<n>;mtime:<millis>`; Drive: `version`/`md5Checksum`; Gmail: `gmail:<id>;hist:<historyId>`).
   Unchanged checksum + `INDEXED` status = skipped entirely.
4. **`grab` is stateless and idempotent.** All pagination state lives in `CursorPosition`; the same page may
   be replayed after a crash. Files pass as `fileRef` (a path), never bytes — Mongo's 16 MB cap.
5. **Embedding dimension is baked into the index mapping.** `app.embedding.dimension=768` is written into the
   `knn_vector` mapping by `OpenSearchIndexInitializer` at index creation. A different-width model needs a new
   physical index (`chunks_v2`) + alias flip + full re-index. Code only ever talks to the `chunks` alias.
6. **Indexing is an idempotent replace:** `deleteByEntity(id)` then `indexChunks(...)`, chunk id
   `entityId_ordinal`. Chunking config changes are direct updates — existing chunks are not re-chunked;
   opt in per entity via `POST /api/index/entities/{id}/reindex`.
7. Permits (`InMemoryPermitService`, scopes `global` / `connector:<TYPE>` / `knowledge:<id>`) are
   **single-node only**. Permit TTL must be `>=` lease TTL, and lease TTL must exceed worst-case single-page time.

## Style (differs from Java defaults)

- Logging is `java.util.logging.Logger`: `private static final Logger LOG = Logger.getLogger(X.class.getName())`.
  Not SLF4J, not JBoss `Log`.
- Records for all DTOs and domain model, with `@param` Javadoc on components.
- Constructor injection with `@Inject` on the constructor + `private final` fields — but `@ConfigProperty`
  fields are **package-private on purpose** so unit tests can set them directly (`runner.embedBatch = 64;`).
- **A text config property that may legitimately be unset is `Optional<String>`, read through
  `common/ConfigText`** — never a bare `String`, and never `defaultValue = ""`. SmallRye converts an empty
  value to null, and a non-`Optional` injection point then fails startup with *"Failed to load config value
  of type class java.lang.String for: &lt;key&gt;"*. This bites every `${ENV_VAR:}`-backed property the
  moment the env var is unset. Properties that always carry a real value (e.g.
  `app.scheduler.default-interval=1d`) stay plain `String` with a non-empty `defaultValue`.
- Explicit single-type imports, no wildcards; static imports first, then one alphabetical block
  (Spotless enforces this). 4-space indent, ~110 col.
- Javadoc explains *why* — rationale, trade-offs, invariants. Match that density.

## Testing

JUnit 5, plain `Assertions.*` — no Mockito, no AssertJ. Tests hand-wire constructors and reuse the in-memory
fakes in `src/test/java/io/personalassistant/testsupport/` (`InMemory*Repository`, `RecordingSearchIndex`,
`FakeEmbeddingProvider`, `StubConnector`, `AlwaysGrantPermitService`, `TestData`). Tests need no Mongo,
OpenSearch, or network — keep it that way.

`api.resource.*` has **no HTTP-layer coverage** and `rest-assured` is already a declared dependency. Prefer
adding `@QuarkusTest` + rest-assured tests for resources; the untested-adapter gap (`storage.mongo`,
`DefaultSearchService`, `DefaultSearchAgent`, `OnnxEmbeddingProvider`) is worth closing too.

## Local dev

`app.embedding.provider=onnx-bge` is the shipped default but `app.embedding.onnx.model-path` is empty, so
embedding throws until a model is exported. For local dev set `app.embedding.provider=local-hashing`.
Optional env vars: `GROQ_API_KEY` (answers), `GEMINI_API_KEY` (hosted embeddings),
`GOOGLE_OAUTH_CLIENT_ID`/`_SECRET` (Gmail/Drive token refresh). No `.env` file — bare env vars.

Credentials live on `Connection` (`connections` collection, one default per `SourceType`), not on `Knowledge`.

## Docs

Updating the relevant `docs/*.md` is part of "done" for any behavior or schema change. The docs were
reconciled against the code on 2026-07-31, so treat them as accurate and keep them that way — if a
change makes one wrong, fix it in the same change rather than leaving a note.

Domain vocabulary is `Knowledge` / `Entity` / `Iterable` / `Cursor` / `Connection` / `Chunk`. The
older `Source` / `Document` names are gone; don't reintroduce them.

Where things are documented: `docs/knowledge-lifecycle.md` and `docs/knowledge-edit-design.md` (add /
edit / pause / delete semantics), `docs/indexing-design.md` + `docs/indexing-implementation.md` (the
two stages, and the config reference in §6), `docs/connectors.md` (the `SourceConnector` SPI and
`Connection` auth), `docs/parsing-and-chunking.md`, `docs/providers.md` (embedding + LLM providers,
including the ONNX model export), `docs/mongodb-schema.md` / `docs/opensearch-index.md`
(persistence), `docs/limitations.md` (L1–L5 accepted gaps — don't "fix" these unprompted).
`application.properties` is the tiebreaker for any config default.

## Repo etiquette

- Branch from `main`, not from the current feature branch.
- Adding or changing an endpoint means updating `personal-search-assistant.postman_collection.json` in the
  same change.
- Commit subjects are freeform lowercase (`app knowledge indexing changes #3 | postman collection`) — not
  Conventional Commits.
