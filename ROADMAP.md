# Roadmap — Personal Search Assistant

A prioritized backlog, ordered by priority × usefulness. Effort is a rough estimate
(S / M / L). See `ARCHITECTURE.md` for the ports & adapters each item plugs into, and
`docs/limitations.md` for the tracked gaps referenced below.

## Where the project is today

The ingestion + indexing engine is mature and well-tested (~6.4k LOC main, ~3k test):
cursor scheduling, leasing/fencing, concurrency permits, fairness, forward + backfill
sync, hybrid retrieval with RRF fusion, Mongo ↔ OpenSearch, and knowledge-edit Phase 1
are all real.

The pieces that make it a *search assistant* rather than a keyword indexer are still
stubs or baselines:

- **Grounded answers throw.** `StubLlmProvider.complete` raises `UnsupportedOperationException`,
  so `answer: true` fails. The agent's grounded-prompt-with-citations wiring already exists.
- **"Semantic" search isn't semantic.** `LocalHashingEmbeddingProvider` is a hashing trick,
  not a real model, so SEMANTIC / HYBRID modes don't retrieve on meaning.
- **One connector.** Only `LOCAL_FS` exists; `GMAIL`, `SLACK`, `GOOGLE_DRIVE`, `NOTION` are
  enum values with no adapter.
- **No reranker, no auth, no evaluation harness.**

## Tier 1 — Make the core promise real (do now)

1. **Real semantic embeddings** (M). Swap `LocalHashingEmbeddingProvider` for an ONNX
   `all-MiniLM-L6-v2` (local, private) or a hosted embedding API. Config is already pinned
   to 384-dim for the OpenSearch `knn_vector` mapping, so this is a clean `EmbeddingProvider`
   bean swap plus a re-index. Without it, SEMANTIC / HYBRID modes don't retrieve on meaning.

2. **Real LLM provider** (S–M). Implement `LlmProvider` against Ollama (local) or a hosted
   REST endpoint. Today `answer: true` throws. `DefaultSearchAgent` already builds the
   grounded, cited prompt — it just needs a live provider.

These two are the whole reason to build this over `grep` + OpenSearch. Everything below
only matters once they are real.

## Tier 2 — Breadth & quality

3. **A second connector — Gmail or Google Drive** (L). Most personal data lives in the cloud,
   not the local filesystem. Also proves the pluggable `SourceConnector` interface beyond the
   easy no-auth case.

4. **Cross-encoder reranker** (M). Replace `NoopReranker`. Biggest precision win once
   embeddings are real, and it swaps in behind the existing `Reranker` port with no caller
   changes.

5. **Evaluation harness** (M). Recall / relevance measurement so items 1, 3, and 4 can be
   proven to help. Listed as a concern in `DESCRIPTION.md` but nothing exists yet — quality is
   currently unmeasured.

## Tier 3 — Correctness & productionization

6. **Knowledge-edit Phase 2 purge** (M). Tracked gap L2: after a scope shrink, stale
   entities / chunks stay searchable. The staleness marks (`syncGeneration` /
   `lastSeenGeneration`) are already written, so this is the deliberate completion-gated
   cleanup path.

7. **Auth & access control** (M–L). None today; `DESCRIPTION.md` calls it "critical for
   private personal data."

8. **Observability** (S–M). Micrometer metrics + OpenTelemetry tracing across the
   ingest / index / search paths.

9. **Conversation memory / multi-turn** (M). The agent is single-shot; the design calls for
   multi-turn context.

10. **OCR for images** (S). Wire Tesseract into Tika so scanned PDFs and images are parseable
    (added as a higher-priority `ContentParser`, no caller changes).

## Tier 4 — Scale & infra (later)

11. **Async pipeline via Kafka / RabbitMQ** (L), replacing Mongo polling. The stage boundary
    and ports don't change.
12. **Redis KV cache** (S) for hot lookups, once hot paths are identified.
13. **L1 reconcile sweep** (S) for the pause/resume park-vs-rearm race. Self-healing today,
    so low urgency.
14. **Rate limiting / API gateway, k8s manifests, a thin query UI**, plus the remaining
    connectors (Slack, Notion).

## Suggested (not in existing docs)

The evaluation harness (5), conversation memory (9), the reconcile sweep (13), and a minimal
query UI.

## Recommended next step

Do **#1 (real embeddings)** and **#2 (real LLM)** first — smallest surface, and together they
flip the product from "indexes text" to "answers questions about your data."
