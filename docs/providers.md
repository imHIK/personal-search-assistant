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

- **Same protocol (OpenAI-compatible):** no code. For embeddings, point the hosted adapter's
  `base-url` + `model` + `api-key` properties at it; for the LLM, add an `LLM` connection in the console
  ([below](#llm-connections)). Covers Gemini, Jina, Mistral, Groq, OpenRouter, Together, Ollama.
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

Set embedding ceilings with `app.ratelimit.embedding.rules`, in the same `"<permits>/<window>"` form the
connectors use (`10/1s,500/1m,10000/1d`); blank means unlimited. The shipped rules are `6/1m,120/1d`,
sized for Gemini's free tier. Embedding windows are keyed by provider id, so switching provider switches
window. **LLM ceilings are not here:** each `LLM` connection carries its own rules, set on the connection
in the console — see [LLM connections](#llm-connections).

**Background calls stop short of the ceiling, so search keeps a reserve.** Indexing and search embed
through the same account, and a backfill would otherwise spend the whole day's quota and leave every
search without a vector. `app.ratelimit.embedding.background.rules` is a lower ceiling that
`RateLimitPolicies` applies to `WAIT`-mode calls — indexing — **on the same key** as the shared rules:

```properties
app.ratelimit.embedding.rules=6/1m,120/1d             # what the provider allows; searches may use all of it
app.ratelimit.embedding.background.rules=4/1m,100/1d  # indexing stops here: 2/min and 20/day stay for search
```

A window is counted per key and window length, so the two are one counter with two stopping points,
not two budgets: the reserve is only the gap, a quiet day's unused reserve is not lost to a split, and
the pair can never exceed the provider's real ceiling. Match the window lengths (`1m` with `1m`, `1d`
with `1d`) — an unmatched background rule is merely an extra ceiling of its own. Blank inherits the
shared rules, i.e. no reserve. Both limiter stores time a deferred call to the admission that brings the
count back under *its own* ceiling, which is not the oldest one when searches have pushed the count past
it; the oldest would hand an entity a `nextAttemptAt` at which the window is still full.

This splits one quota; it does not add any. If indexing inside its share is too slow, the fix is a
second provider project (a second key and quota) or the local `onnx-bge` provider, not a larger reserve.

Each rule is a **rolling** window, not a refilling bucket: `60/1d` admits 60 calls back to back and then
nothing until those calls are a day old. That matters on a small quota — a bucket handing one permit
back every 24 minutes gives out a unit too small to finish a single entity, so the entity is claimed,
parsed, chunked and deferred over and over; and against a service counting its own trailing day, the
61st call is a 429 the local limiter thought it had avoided.

**The windows are counted in Redis** (`app.ratelimit.store=redis`, the shipped default), so they survive
a restart and a `quarkusDev` live reload. That matters most for the small daily quotas above. An
in-memory `60/1d` starts empty on every restart while the provider's day keeps counting, so the calls
after a restart are 429s. `app.ratelimit.store=memory` is for tests and setups without Redis, and is only
honest with short windows. There is no fallback between the two: if Redis is down, outbound calls fail
rather than quietly counting in memory. See [L9](./limitations.md#l9--rate-limit-counters-are-per-process-and-lost-on-restart).

What happens on a breach depends on which call it is, and the split is free because the two paths
already have separate entry points:

| Call | Entry point | On breach |
|---|---|---|
| Indexing a backfill | `EmbeddingProvider.embedAll` | **Waits**, then defers — the import slows down |
| Embedding a search query | `EmbeddingProvider.embedQuery` via `QueryEmbedder` | **Fails fast** — the search runs `LEXICAL` and returns 200 with `vectorError` set, hits intact |
| Answering | `LlmProvider.complete` under the `answer` profile | **Fails fast** — 200 with `answerError` set, hits intact |
| A digest's task | `LlmProvider.complete` under the task's profile | **Fails fast** — the run is kept, with `taskError` set |
| Enriching an entity at indexing time | `DefaultMetadataEnricher` | **Waits**, then the indexing pass defers |

The mode belongs to the *call*, not the account: `LlmProfile.rateLimitMode` is empty (fail fast) unless
the caller sets it, and enrichment is the one that does (`withDefaultMode(WAIT)`).

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
| `openai-compat` (default) | `OpenAiCompatibleLlmProvider` | Any OpenAI-compatible chat endpoint: Groq, Gemini, OpenRouter, a local Ollama |
| `none` | `StubLlmProvider` | Throws on use; turns every LLM call off |

`app.llm.provider` and `app.llm.timeout-seconds` are the only LLM properties. **Endpoint, key, model,
temperature, max tokens and rate limits are not configuration at all** — they live on `LLM`
connections, edited in the console, so changing the model needs no restart and no env var.

### LLM connections

An `LLM` connection is added under Accounts → LLM and stored in `connections` like any other account. It
is a `ConnectionKind` bean (`LlmConnectionKind`), so it gets the generic create / edit / test / default /
health-sweep machinery for free:

| Where | Key | Notes |
|---|---|---|
| `auth` | `apiKey` | Empty sends no Authorization header (Ollama) |
| `config` | `baseUrl`, `model` | Required |
| `config` | `profile` | Optional: the profile name this connection serves |
| `config` | `temperature`, `maxTokens` | Optional: **absent is left out of the request**, so the vendor default applies |
| `rateLimit` | rules | This connection's own window, `connection:<id>` |

`LlmProfiles.get(name)` resolves, on every call (an edit applies to the next call):

1. the `LLM` connection whose `config.profile` is `name`;
2. otherwise the default `LLM` connection (the first one created becomes the default);
3. otherwise **it throws**, naming the profile and where to add a connection. Answering turns that into
   `answerError`, a digest into `taskError`, and enrichment into the entity's `enrichment.error` — the
   entity is still indexed. There is deliberately no configuration fallback.

A `DISABLED` connection is skipped. One in `ERROR` is **still used**: with nothing to fall back to,
skipping it would turn a failed health check — possibly a transient network blip on `/models` — into a
certain failure, while a genuinely broken connection fails the call with its real error anyway. Two
connections may not serve the same profile; verification refuses the second.

**Profiles are just names.** A task names one (`answer`, `lite`, or anything a user task picks); which
connection serves it is decided per call. `GET /api/llm-profiles` lists the profiles connections serve
plus those the bundled tasks ask for, so a task editor can offer `answer` and `lite` before any
connection claims them. A profile nobody claims runs on the default connection.

A typical setup is two connections:

| Name | `baseUrl` | `model` | `profile` | Other |
|---|---|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b` | — (make it the default) | `maxTokens` 2048 |
| Gemini lite | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-3.5-flash-lite` | `lite` | `temperature` 0.0, `maxTokens` 4096 |

`lite` (job-fit scoring, enrichment) belongs on Gemini because of Groq's free-tier **tokens per minute**:
Groq rejects any single request whose prompt plus `max_tokens` exceeds the per-minute limit (8000 for
`gpt-oss-120b`) with a 413 that waiting cannot fix, and job-fit judges whole postings — ten of them run to
~60k chars. Give `lite` a generous `maxTokens`: a thinking model's reasoning is charged against it, and a
tight cap truncates the JSON, which JSON mode then rejects server-side (`json_validate_failed`). Nothing
sends `max_tokens` unless a connection sets it, and then a long reply can be cut off with no local signal.

For a local model, add a connection with `baseUrl` `http://localhost:11434/v1`, `model` `llama3.1:8b`
and no key.

**Rate limits are per connection.** Every call is charged to `connection:<id>` with the connection's rules
(falling back to `app.ratelimit.connector.LLM.rules`, unset), so a Groq and a Gemini connection never
share a counter.

**Verification lists models, it does not complete.** `verify` calls `GET {baseUrl}/models` and checks
`model` is offered (Gemini lists `models/<name>`, so a suffix match counts). The health sweep re-runs it
every `app.connections.health-interval`, and a listing costs no tokens. The console's reconnect banner
ignores LLM connections, since its copy is about imports stopping.

The connection's `auth` (the key) is returned by `GET /api/connections` like every other account's
credentials — see `ConnectionResource`. Logs and errors name the connection, endpoint and model, never
the key.

### Tasks, prompts and profiles

An LLM call resolves through three named things, each owning one decision:

```
app.agent.task=answer
   └─ config/prompts.json  tasks.answer
        ├─ prompt:     "answer"   →  prompts.answer  (the text)
        ├─ llmProfile: "answer"   →  the LLM connection serving "answer", else the default  (the model)
        └─ contextChars / maxSources          (the budgets)
```

**A prompt is keyed by task, never by model.** One tied to a model cannot be reused when the model
changes and cannot be shared by two features on different models — so the model lives on the connection
serving the profile, which the task references by name. A task can therefore carry a prompt but never model settings. There is
a test that fails if any prompt mentions a model or provider name.

Adding a second LLM-using feature is a JSON entry naming a profile: no new bean, no new config namespace,
and a connection for that profile only if it should not run on the default.
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
