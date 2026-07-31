# Roadmap — Personal Search Assistant

A prioritized backlog, ordered by priority × usefulness. Effort is a rough estimate
(S / M / L). See `ARCHITECTURE.md` for the ports & adapters each item plugs into, and
`docs/limitations.md` for the tracked gaps referenced below.

## Where the project is today

The ingestion + indexing engine is mature and well-tested (~10.5k LOC main, ~4.9k test):
cursor scheduling, leasing/fencing, concurrency permits, fairness, forward + backfill
sync, hybrid retrieval with RRF fusion, Mongo ↔ OpenSearch, and knowledge-edit Phase 1
are all real.

**The original Tier 1 is done.** The three items that made this a search assistant rather than
a keyword indexer have shipped:

- **Real semantic embeddings.** `OnnxEmbeddingProvider` runs `bge-base-en-v1.5` locally in-JVM via
  DJL + ONNX Runtime, with `OpenAiCompatibleEmbeddingProvider` as the hosted alternative and
  `LocalHashingEmbeddingProvider` kept as the offline dev/test baseline. The active one is chosen by
  `app.embedding.provider`; the `knn_vector` mapping is pinned at **768** dimensions.
- **Real LLM provider.** `OpenAiCompatibleLlmProvider` speaks to Groq / Gemini / Ollama, so
  `answer: true` returns a grounded, cited answer. `StubLlmProvider` survives only as the explicit
  `app.llm.provider=none` opt-out.
- **Credentialed connectors.** `GmailConnector` and `GoogleDriveConnector` ship alongside
  `LocalFsConnector`, proving the `SourceConnector` SPI beyond the no-auth case. Credentials live on
  a reusable `Connection` with OAuth refresh. `SLACK` and `NOTION` remain enum values with no adapter.

Also since: six per-format content parsers with a Tika fallback, four chunking strategies selectable
per knowledge, and knowledge editing (`PATCH /api/knowledge/{id}`).

Still missing: **no reranker, no auth, no evaluation harness, no OCR.**

## Tier 1 — Quality of results (do now)

1. **Evaluation harness** (M). Recall / relevance measurement. Now the most valuable item on the
   list: embedding model, chunking strategy, and the reranker below are all tunable knobs, and none
   of them can be shown to help without measurement. Listed as a concern in `DESCRIPTION.md`;
   nothing exists yet, so quality is currently unmeasured.

2. **Cross-encoder reranker** (M). Replace `NoopReranker`. The biggest precision win now that
   embeddings are real, and it swaps in behind the existing `Reranker` port with no caller changes.
   Do it after (1) so the gain is measurable.

## Tier 2 — Breadth & correctness

3. **Knowledge-edit Phase 2 purge** (M). Tracked gap L2: after a scope shrink, stale entities /
   chunks stay searchable. The staleness marks (`syncGeneration` / `lastSeenGeneration`) are already
   written, so this is the deliberate completion-gated cleanup path.

4. **OCR for images and scanned PDFs** (S–M). Tracked gap L3: image-only content extracts to nearly
   nothing today. Wire Tesseract into Tika as a higher-priority `ContentParser` — the CDI registry
   picks it up with no caller changes.

5. **A third connector — Slack or Notion** (M). Cheaper than the first credentialed connector was:
   `Connection`, OAuth refresh, and the `TokenWindowGrabber` base class already exist.

6. **Auth & access control** (M–L). None today; `DESCRIPTION.md` calls it "critical for private
   personal data."

## Tier 3 — Productionization

7. **Observability** (S–M). Micrometer metrics + OpenTelemetry tracing across the
   ingest / index / search paths.

8. **HTTP-layer tests** (S). `rest-assured` is a declared dependency with zero usages and
   `api.resource.*` has no coverage at all — including the exception → status mapping.

9. **CI** (S). No pipeline exists; running `./gradlew build` (which covers `spotlessCheck` + tests)
   on every push is the whole ask.

10. **Conversation memory / multi-turn** (M). The agent is single-shot; the design calls for
    multi-turn context.

11. **Large-file handling** (M). Tracked gap L4: whole-file-in-memory parsing, and oversized files
    are silently skipped with no signal surfaced to the user.

## Tier 4 — Scale & infra (later)

12. **Redis-backed `PermitService`** (M). `InMemoryPermitService` is single-node only, so this is
    the prerequisite for running more than one instance.
13. **Async pipeline via Kafka / RabbitMQ** (L), replacing Mongo polling. The stage boundary
    and ports don't change.
14. **Redis KV cache** (S) for hot lookups, once hot paths are identified.
15. **Schedule-driven discovery** (S). Tracked gap L5: `IterableDiscoveryScheduler` runs on a flat
    60-minute interval regardless of a knowledge's configured schedule.
16. **L1 reconcile sweep** (S) for the pause/resume park-vs-rearm race. Self-healing today,
    so low urgency.
17. **Rate limiting / API gateway, k8s manifests, a thin query UI.**

## Recommended next step

Do **#1 (evaluation harness)** first. Every remaining quality item — the reranker, chunking-strategy
choice, embedding-model choice — is a guess until there is a number to move.
