# Embedding & LLM Providers

Both the embedding model and the answering LLM are **ports with swappable adapters**, selected at
runtime by a single config key — no code change to swap a model. This is the same registry idea used
for connectors and parsers.

- Embedding port: `EmbeddingProvider` — selected by `app.embedding.provider`.
- LLM port: `LlmProvider` — selected by `app.llm.provider`.

Each concrete adapter is a CDI bean carrying the `@ProviderImpl` qualifier and a stable
`providerId()`. A selector (`EmbeddingProviderSelector` / `LlmProviderSelector`) reads the config key,
finds the matching adapter, and produces it as the `@Default` bean that `DefaultSearchService`,
`IndexingRunner`, and `DefaultSearchAgent` inject unchanged.

## Adding a new model

- **Same protocol (OpenAI-compatible):** just config. Point the hosted adapter at the new
  `base-url` + `model` + `api-key`. Covers Gemini, Jina, Mistral, Groq, OpenRouter, Together, Ollama.
- **New protocol:** add one `@ProviderImpl`-qualified bean implementing the port with a new
  `providerId()`, then set the config key to it. Nothing else changes.

## Embedding providers

| `app.embedding.provider` | Adapter | Notes |
|---|---|---|
| `openai-embed` (default) | `OpenAiCompatibleEmbeddingProvider` | Hosted `/embeddings` (Gemini default, 3072-dim model requested at 768). Needs `GEMINI_API_KEY` |
| `onnx-bge` | `OnnxEmbeddingProvider` | Local, private, in-JVM ONNX (`bge-base-en-v1.5`, 768-dim). Needs an exported model — see below |
| `local-hashing` | `LocalHashingEmbeddingProvider` | Offline non-semantic baseline for dev/tests |

The vector width `app.embedding.dimension` (768) is **baked into the OpenSearch `knn_vector`
mapping**. Switching to a different-width model requires a new physical index and a full re-index
(invariant 5).

> **Same width is not the same as compatible.** Both real providers above are 768-dim, so a switch
> between them is accepted by the index — and that is exactly the trap. Vectors from two different
> models are not comparable to each other or to a query embedded by the new one, so the corpus ends
> up half-and-half and semantic search quietly degrades, with no error anywhere. **Changing the
> embedding model means re-embedding everything**, even at an identical dimension:
>
> ```bash
> curl -X POST localhost:8080/api/index/knowledge/{id}/reindex   # per knowledge; returns {"queued": n}
> ```
>
> That re-indexes from stored content — no re-fetch from the source. Run it for every knowledge after
> changing `app.embedding.provider` or `app.embedding.openai.model`, and expect the embedding cost of
> your whole corpus in one go.

### Choosing an embedding provider

The practical constraint is usually rate limits, not quality. A first backfill embeds every chunk of
every entity; after that the checksum skip means only new and changed items are embedded, so steady
state is far smaller than day one. Size the provider for the backfill, not the steady state.

| Want | Use | Why |
|---|---|---|
| No limits, no cost, private | `onnx-bge` | Runs in-JVM. Nothing to rate-limit, so a large backfill is only CPU time. One-time model export (below) |
| Hosted, high volume | `openai-embed` pointed at a paid endpoint | Any OpenAI-compatible `/embeddings` works. Ask for `dimensions: 768` so the existing index still fits |
| Casual use | `openai-embed` on Gemini's free tier (the shipped default) | Fine for a modest corpus; its free quota is small enough that a first backfill of a few thousand chunks will hit it |

A `429` from the hosted provider is not data loss, and it is now handled ahead of time as well as
after the fact. The response's `Retry-After` is read by `common.http.OutboundHttp` and fed into the
limiter, so the *next* batch waits rather than rediscovering the limit; and if the wait is longer than
`app.ratelimit.max-wait-seconds`, `IndexingRunner` records the reopening instant as the entity's
`nextAttemptAt` and moves on. Either way a rate-limited backfill finishes slowly rather than failing —
it does mean a large first import can take hours on a free tier.

### Rate limiting the model endpoints

Set ceilings with `app.ratelimit.embedding.rules` and `app.ratelimit.llm.rules`, in the same
`"<permits>/<window>"` form the connectors use (`10/1s,500/1m,10000/1d`); blank means unlimited, which
is the shipped default. Windows are keyed by provider id, so switching provider switches window.

Each rule is a **rolling** window, not a refilling bucket: `60/1d` admits 60 calls back to back and then
nothing until those calls are a day old. That matters on a small quota — a bucket handing one permit
back every 24 minutes gives out a unit too small to finish a single entity, so the entity is claimed,
parsed, chunked and deferred over and over; and against a service counting its own trailing day, the
61st call is a 429 the local limiter thought it had avoided.

What happens on a breach depends on which call it is, and the split is free because the two paths
already have separate entry points:

| Call | Entry point | On breach |
|---|---|---|
| Indexing a backfill | `EmbeddingProvider.embedAll` | **Waits**, then defers — the import slows down |
| Embedding a search query | `EmbeddingProvider.embedQuery` | **Fails fast** — the search still returns lexical hits |
| Answering | `LlmProvider.complete` under the `answer` profile | **Fails fast** — 200 with `answerError` set, hits intact |
| A background digest | `LlmProvider.complete` under a profile with `rate-limit-mode=wait` | **Waits** |

The LLM's behaviour rides on the profile rather than the provider because the same bean serves both a
user's request thread and the digest scheduler:

```properties
app.llm.profile.answer.rate-limit-mode=fail-fast
app.llm.profile.digest.rate-limit-mode=wait
```

Absent, it is fail-fast — the safe default for the only caller that names no background profile.

**Profiles are discovered, never declared.** `LlmProfiles.get` looks keys up dynamically, so a new
profile is created by adding properties — no bean, no injection point, no code change. `names()`
enumerates them by scanning `Config.getPropertyNames()` for the `app.llm.profile.` prefix, which is
what `GET /api/llm-profiles` serves: a task editor can then offer the models that actually exist
rather than asking for a name to be typed. That matters because an **unknown profile name does not
throw** — it resolves to an inherit-everything profile with a warning, so a mistyped name silently
runs on the provider default. A picker is the cheapest way to make that unreachable.

`app.embedding.dimension` deliberately has **no `defaultValue`** at any of its injection points. It
is the one config key that silently corrupts the index if guessed — a missing property would
otherwise build a `knn_vector` mapping of some arbitrary width that the configured model's vectors
do not fit. An absent property fails startup instead, which is the outcome you want.

Whatever the provider, it must return exactly one non-null vector per input text, positionally
aligned. `IndexingRunner` enforces this before anything is written, because a chunk that reaches
OpenSearch without a vector is accepted without error, counted as indexed, and then permanently
invisible to semantic search.

### Local ONNX setup (`onnx-bge`)

Export the model to ONNX once, then point the app at the directory:

```bash
pip install "optimum[exporters]"
optimum-cli export onnx --model BAAI/bge-base-en-v1.5 ./models/bge-base-en-v1.5
```

That directory will contain `model.onnx`, `tokenizer.json`, and `config.json`. Then:

```properties
app.embedding.provider=onnx-bge
app.embedding.onnx.model-path=/absolute/path/to/models/bge-base-en-v1.5
```

BGE uses CLS pooling + L2 normalization (the defaults). The model loads lazily on first use, so the
app boots fine even when this provider isn't active.

### Hosted setup (`openai-embed`)

```properties
app.embedding.provider=openai-embed
# Gemini (default). For another provider, change base-url + model.
app.embedding.openai.base-url=https://generativelanguage.googleapis.com/v1beta/openai
app.embedding.openai.model=models/gemini-embedding-001
app.embedding.openai.api-key=${GEMINI_API_KEY:}
app.embedding.openai.dimensions=768
```

Get a free key from Google AI Studio and export it as `GEMINI_API_KEY`.

Note the `models/` prefix: Gemini's compatibility layer wants the full resource name. A bare
`gemini-embedding-001` returns **404 "Requested entity was not found"** — the same message a retired
model gives, so check what the key can actually see before concluding the model is gone:

```bash
curl -s $BASE/models -H "Authorization: Bearer $GEMINI_API_KEY" | jq -r '.data[].id' | grep embed
```

`gemini-embedding-001` is natively **3072**-dim, which does not fit the 768 `knn_vector` mapping.
`app.embedding.openai.dimensions` is sent as the OpenAI `dimensions` parameter to request a 768-wide
vector instead; the model is Matryoshka-trained, so that prefix is a genuine embedding rather than a
truncation artefact, and the index's `cosinesimil` space re-normalises internally so no extra
normalization step is needed. Set it to `0` to omit the parameter and take the model's native width —
which then has to equal `app.embedding.dimension`.

If the API ignores the parameter (some OpenAI-compatible servers do), the provider fails on the first
batch with a message naming both widths rather than writing vectors the index cannot hold. Verify a
new model with one call before switching:

```bash
curl -s $BASE/embeddings -H "Authorization: Bearer $GEMINI_API_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gemini-embedding-001","input":["hello"],"dimensions":768}' | jq '.data[0].embedding | length'
```

> Changing the embedding model invalidates every vector already indexed, **even at identical width** —
> two models embed into different spaces, so old document vectors and new query vectors are not
> comparable. A model swap always means re-indexing the corpus, not just re-mapping the index.

## LLM provider

| `app.llm.provider` | Adapter | Notes |
|---|---|---|
| `openai-compat` (default) | `OpenAiCompatibleLlmProvider` | Hosted (Groq/Gemini/…) or local Ollama |
| `none` | `StubLlmProvider` | Throws on use; disables `answer:true` |

### Hosted setup (default — Groq free tier)

```properties
app.llm.provider=openai-compat
app.llm.base-url=https://api.groq.com/openai/v1
app.llm.model=llama-3.3-70b-versatile
app.llm.api-key=${GROQ_API_KEY:}
```

Get a free key from the Groq console and export it as `GROQ_API_KEY`. For Gemini instead, set
`base-url=https://generativelanguage.googleapis.com/v1beta/openai`, `model=gemini-2.0-flash`,
`api-key=${GEMINI_API_KEY:}`.

### Local later (Ollama)

```bash
ollama pull llama3.1:8b
```

```properties
app.llm.base-url=http://localhost:11434/v1
app.llm.model=llama3.1:8b
app.llm.api-key=
```

No code change — same adapter, different config.

### LLM profiles (per-role model selection)

The `app.llm.*` keys above are the *provider defaults*. On top of them sit **named profiles**, resolved
from `app.llm.profile.<name>.<key>` by `LlmProfiles`:

```properties
app.llm.profile.answer.model=llama-3.3-70b-versatile
app.llm.profile.answer.temperature=0.2
app.llm.profile.answer.max-tokens=2048

app.llm.profile.lite.model=llama-3.1-8b-instant
app.llm.profile.lite.temperature=0.0
app.llm.profile.lite.max-tokens=512
```

Recognised sub-keys: `base-url`, `model`, `temperature`, `max-tokens`, `api-key`. A caller asks for a
profile by name — the answering *task* names `answer`, via `app.agent.task` — and gets the
provider defaults for everything the profile does not set.

**Why this exists.** LLM use is becoming both frequent and heterogeneous: answering wants a strong
model, index-time enrichment wants a cheap one, a listwise reranker wants a fast one, and they need not
live on the same provider. Without profiles the only way to reach a second model is a second bean with
its own config keys, so every feature would grow the wiring. With them, giving a new feature its own
model is a config edit — the keys are looked up dynamically, so **adding a profile requires no code at
all**. `LlmProvider.complete(profile, system, messages)` is a `default` method, so providers that cannot
vary anything (the stub) are unaffected.

Three behaviours worth knowing:

- **Unset means inherit, and blank counts as unset.** A profile naming only `model` keeps the provider's
  temperature, timeout and endpoint. Blank has to mean "inherit" because a `${ENV_VAR:}`-backed key is
  empty exactly when the variable is unset, and an empty model would otherwise be sent verbatim.
- **A profile that redirects `base-url` must bring its own `api-key`.** It deliberately does *not* fall
  back to the provider's — that would send one vendor's secret to another vendor's host. Sending no key
  is the right default for the main reason to redirect, a local Ollama.
- **An unknown profile name warns and inherits everything** rather than throwing. A misspelled profile on
  a background feature should degrade to the default model, not take the feature offline.

`max-tokens` deserves a note: nothing was sent before profiles existed, so the vendor default applied and
a long list answer could be truncated with no local signal that it had been.

### Tasks, prompts and profiles

An LLM call resolves through three named things, each owning one decision:

```
app.agent.task=answer
   └─ config/prompts.json  tasks.answer
        ├─ prompt:     "answer"   →  prompts.answer  (the text)
        ├─ llmProfile: "answer"   →  app.llm.profile.answer.*  (the model)
        └─ contextChars / maxSources          (the budgets)
```

**A prompt is keyed by task, never by model.** One tied to a model cannot be reused when the model
changes and cannot be shared by two features on different models — so the model lives in the profile,
which the task references by name. A task can therefore carry a prompt but never model settings. There is
a test that fails if any prompt mentions a model or provider name.

Adding a second LLM-using feature is a JSON entry plus a profile: no new bean, no new config namespace.
See [`configuration.md`](./configuration.md).

> `app.embedding.onnx.query-instruction` below is deliberately **not** part of this. It is a model's
> documented input format, not a prompt — meaningless to any other model.

### Query vs document embeddings

Retrieval embedding models are trained **asymmetrically**: a short question and a long passage are not
the same kind of input, and the model is told which it is being given. Both models configured here are
of that kind, and both sides previously went through the identical code path.

`EmbeddingProvider.embedQuery(text)` is a `default` method delegating to `embed(text)`, so a symmetric
provider (the offline hashing baseline) is unchanged. `DefaultSearchService` calls `embedQuery`;
`IndexingRunner` keeps `embedAll`.

- **`onnx-bge`** prepends `app.embedding.onnx.query-instruction` — BGE's published wording — to queries
  only. The separating space is added in code, so the property needs no meaningful trailing whitespace
  (spotless would strip it). Blank it for a symmetric model: the wrong instruction is worse than none.
- **`openai-embed`** has `app.embedding.openai.task-type-enabled=false`, and it must stay off for the
  shipped base-url. `task_type` is a **native** Gemini parameter; the OpenAI-compatible endpoint
  validates strictly and answers `400 Invalid JSON payload received. Unknown name "task_type": Cannot
  find field.`, which fails every embedding call and therefore all indexing. The compat layer exposes no
  way to signal query vs document, so the asymmetry is simply unavailable there.

### What gets embedded

Not the chunk body alone. The `embedContext` field set in `config/field-sets.json` (default `["title"]`, scoped per connector) names fields prefixed to
the text **before embedding**; the stored and displayed text is unchanged. `title`/`uri` resolve against
the chunk, anything else against its metadata (`sheet`, `headingPath`, `rowRange`), and absent fields are
skipped.

This matters more than it sounds. A chunk holding only table rows — `17 Dussehra 20/10/2026 Tuesday` —
shares no term and no semantic signal with a document called `public_holidays_2026.pdf`, so a query about
holidays never retrieved it, and an answer built from the chunks that did rank was silently missing rows.
**Changing the list means re-indexing:** existing vectors were built from a different string.

## Citation markers

The answer prompt (`config/prompts.json`, `prompts.answer.system`) numbers sources 1-based and positional
in `hits`, and asks the model to cite them as **ASCII** `[1]`, grouping several into one marker as
`[1,3,4]`. `AnswerPromptBuilder` keeps that numbering stable even for a hit dropped for budget reasons, so
a number always names the hit at that position.

The bracket *shape* is part of the contract because the console turns each marker into a button that
scrolls to the result it cites. Hosted models nonetheless emit the fullwidth CJK pair `【1】` often enough that
the prompt's instruction alone is not a guarantee, so `frontend/src/lib/answerMarkdown.ts` accepts both
families (and the fullwidth comma) rather than rendering an unmatched marker as dead text. Prompt first,
parser as the backstop — a new marker shape in the wild is a one-line change to `INLINE_PATTERN`.

## Try it

```bash
# hybrid search with a grounded, cited answer
curl -s localhost:8080/api/search -H 'content-type: application/json' -d '{
  "query": "quarterly revenue", "topK": 5, "mode": "HYBRID", "answer": true
}'
```
