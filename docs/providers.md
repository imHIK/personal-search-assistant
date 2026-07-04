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
| `onnx-bge` (default) | `OnnxEmbeddingProvider` | Local, private, in-JVM ONNX (`bge-base-en-v1.5`, 768-dim) |
| `openai-embed` | `OpenAiCompatibleEmbeddingProvider` | Hosted `/embeddings` (Gemini default, 768-dim) |
| `local-hashing` | `LocalHashingEmbeddingProvider` | Offline non-semantic baseline for dev/tests |

The vector width `app.embedding.dimension` (768) is **baked into the OpenSearch `knn_vector`
mapping**. The two real providers above are both 768-dim, so you can switch between local and hosted
with no re-index. Switching to a different-width model requires re-mapping the index and re-indexing.

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
app.embedding.openai.model=text-embedding-004
app.embedding.openai.api-key=${GEMINI_API_KEY:}
```

Get a free key from Google AI Studio and export it as `GEMINI_API_KEY`.

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
