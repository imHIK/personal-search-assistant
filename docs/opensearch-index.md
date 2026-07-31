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

## Index: `chunks_v1`

Versioned name (`_v1`) so we can reindex into `chunks_v2` and flip an alias with zero
downtime. Application always talks to the alias `chunks`.

Created at startup by `OpenSearchIndexInitializer` if the physical index is absent (it logs a
warning and continues if OpenSearch is unreachable). `dimension` is interpolated from
`app.embedding.dimension`.

```jsonc
PUT /chunks_v1
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
> (768 = `bge-base-en-v1.5`, and also `text-embedding-004`, so those two are interchangeable with
> no re-index). Moving to a model of a *different* width is not a config change: it needs a new
> physical index (`chunks_v2`), a full re-index, and an alias flip. Changing
> `app.embedding.dimension` alone against an existing index will just make writes fail.
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

After fusion the `Reranker` port can reorder the top ~20 for final precision. The shipped
implementation is `NoopReranker` — a cross-encoder is tracked on the roadmap.

---

## Filtering & permissions

Every query carries a `filter` on `knowledgeId` (and later, allowed-scope ids). Because filtering is
a first-class clause, access control is enforced **at retrieval time** — a user can never be shown a
chunk from a knowledge they aren't permitted to see.

`SearchQuery.filters` is a free-form `field → value` map applied as `term` clauses, with the key used
**verbatim as the field name**. That means callers can filter on any indexed field — top-level
keywords like `sourceType`, `iterableId`, or `uri`, or a dotted path into `metadata` — without a
code change.

---

## Operational notes

- **Alias indirection**: app reads/writes `chunks`; physical index is `chunks_vN`.
- **Reindex flow**: build `chunks_v2` from Mongo → verify → `POST _aliases` atomic swap →
  drop `chunks_v1`.
- **Bulk indexing**: chunks are written with the `_bulk` API during indexing runs.
- **Deletes**: `_delete_by_query` on `entityId`, `knowledgeId`, or `(knowledgeId, iterableId)`
  mirrors the Mongo cascades.
- **Doc id = `chunkId` = `<entityId>_<ordinal>`**, and indexing is a `deleteByEntity` +
  `indexChunks` replace — so re-indexing the same entity is idempotent whether the new chunk count
  is larger or smaller than the old one.

> All of this sits behind the `SearchIndex` port. Swapping OpenSearch for Elasticsearch,
> or splitting vector search into a dedicated DB later, means one new adapter — the domain
> and REST layers are untouched.
