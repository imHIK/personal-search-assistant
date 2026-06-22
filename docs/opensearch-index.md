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
  "mappings": {
    "properties": {
      "chunkId":    { "type": "keyword" },
      "documentId": { "type": "keyword" },
      "sourceId":   { "type": "keyword" },   // filter by source / permission scope
      "sourceType": { "type": "keyword" },

      "text":       { "type": "text", "analyzer": "standard" },   // BM25 field
      "title":      { "type": "text", "analyzer": "standard" },

      "embedding": {
        "type": "knn_vector",
        "dimension": 384,                    // must match the embedding model
        "method": {
          "name": "hnsw",
          "engine": "lucene",
          "space_type": "cosinesimil",
          "parameters": { "m": 16, "ef_construction": 128 }
        }
      },

      "ordinal":    { "type": "integer" },
      "uri":        { "type": "keyword" },    // for citations
      "metadata": {                            // filterable facets
        "properties": {
          "author":     { "type": "keyword" },
          "labels":     { "type": "keyword" },
          "page":       { "type": "integer" },
          "createdAt":  { "type": "date" },
          "modifiedAt": { "type": "date" }
        }
      },
      "indexedAt":  { "type": "date" }
    }
  }
}
```

`dimension` is pinned to the embedding model (384 = `all-MiniLM-L6-v2`). Changing models =
reindex into a new index version; the alias flip keeps clients unaware.

---

## Hybrid query shape

Run lexical and vector retrieval, then fuse. Two common approaches:

**A. Single hybrid query** (BM25 filter context + k-NN), simple to start:

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
        { "terms": { "sourceId": ["src_local_documents"] } }   // permission / scope
      ]
    }
  }
}
```

**B. Rank fusion (recommended for quality)** — retrieve top-K from each method
independently, then combine with **Reciprocal Rank Fusion (RRF)** in the `Retriever`
adapter. This avoids hand-tuning score scales between BM25 and cosine similarity. The
`Retriever` port hides which approach we use.

After fusion, the `Reranker` (cross-encoder) can reorder the top ~20 for final precision.

---

## Filtering & permissions

Every query carries a `filter` on `sourceId` (and later, allowed-scope ids). Because
filtering is a first-class clause, access control is enforced **at retrieval time** — a
user can never be shown a chunk from a source they aren't permitted to see.

---

## Operational notes

- **Alias indirection**: app reads/writes `chunks`; physical index is `chunks_vN`.
- **Reindex flow**: build `chunks_v2` from Mongo → verify → `POST _aliases` atomic swap →
  drop `chunks_v1`.
- **Bulk indexing**: chunks are written with the `_bulk` API during indexing runs.
- **Deletes**: delete-by-query on `documentId` (or `sourceId`) mirrors Mongo cascades.
- **Doc id = `chunkId`** so re-indexing the same chunk overwrites (idempotent).

> All of this sits behind the `SearchIndex` port. Swapping OpenSearch for Elasticsearch,
> or splitting vector search into a dedicated DB later, means one new adapter — the domain
> and REST layers are untouched.
