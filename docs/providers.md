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
mapping**. The two real providers above are both 768-dim, so you can switch between local and hosted
with no re-index. Switching to a different-width model requires re-mapping the index and re-indexing.

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

## Try it

```bash
# hybrid search with a grounded, cited answer
curl -s localhost:8080/api/search -H 'content-type: application/json' -d '{
  "query": "quarterly revenue", "topK": 5, "mode": "HYBRID", "answer": true
}'
```
