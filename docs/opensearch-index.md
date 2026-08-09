# OpenSearch Index — the query engine

OpenSearch is a **derived** store optimized purely for retrieval. It is rebuildable from
MongoDB at any time. We index at **chunk granularity** because that is the unit we embed
and the unit we want to rank.

One index handles both retrieval modes:
- **Lexical (BM25)** over analyzed text — exact terms, names, codes.
- **Semantic (k-NN)** over the embedding vector — meaning / paraphrase.

Running both and combining them = **hybrid search**, which is the main reason to use
OpenSearch instead of a pure vector DB.

---

## Index: `chunks_v2_768`

Versioned name (the `v2_768` suffix records the baked-in vector width) so we can reindex into a new
physical index and flip an alias with zero downtime. Application always talks to the alias `chunks`.

Created at startup by `OpenSearchIndexInitializer` if the physical index is absent (it logs a
warning and continues if OpenSearch is unreachable). `dimension` is interpolated from
`app.embedding.dimension`.

```jsonc
PUT /chunks_v2_768
{
  "settings": {
    "index": {
      "knn": true,                       // enable vector search
      "number_of_shards": 1,
      "number_of_replicas": 0            // bump for prod
    }
  },
  "aliases": { "chunks": {} },
  "mappings": {
    "properties": {
      "chunkId":     { "type": "keyword" },   // = doc id: "<entityId>_<ordinal>"
      "entityId":    { "type": "keyword" },   // delete-by-entity on re-index
      "knowledgeId": { "type": "keyword" },   // filter by knowledge / permission scope
      "iterableId":  { "type": "keyword" },   // filter by sub-stream (folder, label, channel)
      "sourceType":  { "type": "keyword" },

      "text":        { "type": "text", "analyzer": "standard" },   // BM25 field
      "title":       { "type": "text", "analyzer": "standard" },

      "embedding": {
        "type": "knn_vector",
        "dimension": 768,                    // must match the embedding model
        "method": {
          "name": "hnsw",
          "engine": "lucene",
          "space_type": "cosinesimil",
          "parameters": { "m": 16, "ef_construction": 128 }
        }
      },

      "ordinal":   { "type": "integer" },
      "uri":       { "type": "keyword" },    // for citations
      "metadata":  { "type": "object" },     // dynamic — connector/parser-supplied facets
      "indexedAt": { "type": "date" }
    }
  }
}
```

> **`dimension` is pinned to the embedding model and baked in at index-creation time**
> (768 = `bge-base-en-v1.5` natively; `gemini-embedding-001` is natively 3072 but is asked for 768
> via `app.embedding.openai.dimensions`, so it fits the same mapping). Moving to a model whose width
> you cannot request is not a config change: it needs a new physical index (e.g. `chunks_v3_1024`), a full
> re-index, and an alias flip. Changing `app.embedding.dimension` alone against an existing index
> will just make writes fail.
>
> Fitting the mapping is *not* the same as being interchangeable. Two models embed into different
> vector spaces, so swapping one for another invalidates every vector already stored even at equal
> width — old document vectors and new query vectors simply are not comparable, and semantic hits
> degrade to noise rather than to lower recall. Re-index the corpus after any model change.
>
> `metadata` is mapped as a plain `object` with dynamic sub-fields rather than a fixed property
> list, so connectors and parsers can attach whatever facets they have without a mapping change.

---

## Hybrid query shape

Run lexical and vector retrieval, then fuse. Two common approaches:

**A. Single hybrid query** (BM25 `should` + k-NN), simple to start:

```jsonc
POST /chunks/_search
{
  "size": 20,
  "query": {
    "bool": {
      "should": [
        { "match": { "text": { "query": "<user query>", "boost": 1.0 } } },
        { "knn": { "embedding": { "vector": [/* query embedding */], "k": 50 } } }
      ],
      "filter": [
        { "terms": { "knowledgeId": ["kn_8f3a..."] } }   // permission / scope
      ]
    }
  }
}
```

**B. Rank fusion (what we do)** — retrieve top-K from each method independently, then combine with
**Reciprocal Rank Fusion (RRF)** in `HybridRetriever`. This avoids hand-tuning score scales between
BM25 and cosine similarity. The `Retriever` port hides which approach we use, and `SearchQuery.mode`
selects `LEXICAL` / `SEMANTIC` / `HYBRID` (the query embedding is skipped entirely for `LEXICAL`).

> **Filter placement matters on the vector leg.** Note that example A above puts the scope filter in
> the surrounding `bool.filter`, *outside* the `knn` clause. That is post-filtering: OpenSearch picks
> the global `k` nearest neighbours first and then discards the ones that do not match, so scoping a
> search to one knowledge in a large corpus returns far fewer hits than `k` — sometimes none, while
> plenty of relevant chunks exist. `OpenSearchSearchIndex.vectorBody` therefore nests the filter
> **inside** the knn clause:
>
> ```jsonc
> { "knn": { "embedding": {
>     "vector": [/* ... */],
>     "k": 20,
>     "filter": { "bool": { "filter": [ { "terms": { "knowledgeId": ["kn_8f3a..."] } } ] } }
> } } }
> ```
>
> Nested, the filter is honoured during HNSW graph traversal (with an automatic exact-search fallback
> when the filtered set is small), so `k` counts *matching* documents. This requires the `lucene`
> engine, which the mapping above already pins — no re-index is involved. The BM25 leg keeps its
> filter in `bool.filter`, where it is applied during scoring and is already correct.

After fusion the `Reranker` port can reorder the top ~20 for final precision. The shipped
implementation is `NoopReranker` — a cross-encoder is tracked on the roadmap.

---

## Filtering & permissions

Every query carries a `filter` on `knowledgeId` (and later, allowed-scope ids).

> **This is scoping, not access control.** The app has no authentication or authorization of any kind
> today (see [`limitations.md`](./limitations.md) and `ROADMAP.md`), so the `knowledgeId` filter only
> restricts a query to what the *caller asked for* — it does not restrict what a caller is *allowed*
> to ask for. Retrieval-time enforcement is the intended shape once identity exists; it is not a
> property the system has now.

`SearchQuery.filters` is a free-form `field → value` map applied as `term` clauses, with the key used
**verbatim as the field name**. That means callers can filter on any indexed field — top-level
keywords like `sourceType`, `iterableId`, or `uri`, or a dotted path into `metadata` — without a
code change.

---

## Operational notes

- **Alias indirection**: app reads/writes `chunks`; physical index is `chunks_v2_768`.
- **Reindex flow**: build the new physical index from Mongo → verify → `POST _aliases` atomic swap →
  drop the old one.
- **Bulk indexing**: chunks are written with the `_bulk` API during indexing runs. A bulk whose
  response carries `errors: true` raises — a partially-rejected write must not be recorded as a
  success, or the entity claims a chunk count the index does not hold and is never retried.
- **Deletes**: `_delete_by_query` on `entityId`, `knowledgeId`, or `(knowledgeId, iterableId)`
  mirrors the Mongo cascades.
- **Doc id = `chunkId` = `<entityId>_<ordinal>`**, and indexing is a `deleteByEntity` +
  `indexChunks` replace — so re-indexing the same entity is idempotent whether the new chunk count
  is larger or smaller than the old one.

> All of this sits behind the `SearchIndex` port. Swapping OpenSearch for Elasticsearch,
> or splitting vector search into a dedicated DB later, means one new adapter — the domain
> and REST layers are untouched.
