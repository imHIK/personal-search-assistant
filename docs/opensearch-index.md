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

## Index: `chunks_v3_768`

Versioned name (the suffix records the schema version and the baked-in vector width) so we can reindex
into a new physical index and flip an alias with zero downtime. Application always talks to the alias
`chunks`.

> **v2 → v3 is the analysis change.** `text`/`title` moved from the `standard` analyzer to a stemming,
> stopword-filtering English analyzer and gained a `raw` keyword sub-field. Analysis cannot be altered on
> a live index, so a new physical index is the only way to adopt it. `standard` only tokenizes and
> lowercases, which means "holidays" and "holiday" are different terms and every stopword in a query is
> scoreable — half of why a conversational query used to rank unrelated documents (the other half being
> the query shape, `app.search.lexical.minimum-should-match`).
>
> On startup the initializer creates `chunks_v3_768` **without** the alias if an older index still holds
> it, and logs a warning. That is deliberate: an alias resolving to two indices fails writes outright and
> silently doubles reads, and auto-flipping would repoint live search at an empty index. Re-index, verify,
> then swap.

Created at startup by `OpenSearchIndexInitializer` if the physical index is absent (it logs a
warning and continues if OpenSearch is unreachable). `dimension` is interpolated from
`app.embedding.dimension`.

> **Typed `metadata` sub-fields, applied additively.** `metadata` stays a dynamic object — carrying
> arbitrary connector facets is the point of it — but the fields a *filter* compares on cannot be left
> to dynamic inference, because inference is settled by whichever document is indexed first. One board
> emitting `compMin` as a string, or the first posting simply not having it, would fix the field as
> `text` for the life of the index and make every later numeric comparison lexicographic or empty.
> `company`, `location`, `board`, `seniority`, `dedupeKey`, `applyUrl`, `team` and `compCurrency` are
> `keyword`; `remote` is `boolean`; `sourceRank` is `integer`; `compMin`/`compMax` are `long`;
> `postedAt` is `date`.
>
> Adding sub-fields to an existing object mapping is **additive**, which OpenSearch accepts in place, so
> `ensureMetadataMapping()` re-sends them on every boot (idempotent) and an index created before these
> fields existed picks them up — **no alias flip and no re-index**. Invariant 5 is untouched: the
> embedding width does not move. A field that has *already* been indexed with a conflicting dynamic type
> is the one case this cannot fix; the failure is logged, and correcting it genuinely does need a new
> physical index.

```jsonc
PUT /chunks_v3_768
{
  "settings": {
    "index": {
      "knn": true,                       // enable vector search
      "number_of_shards": 1,
      "number_of_replicas": 0            // bump for prod
    },
    "analysis": {                        // stemming + English stopwords; see the v2 -> v3 note above
      "analyzer": {
        "psa_text": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "english_possessive_stemmer", "english_stop", "english_stemmer"]
        }
      },
      "filter": {
        "english_stop":               { "type": "stop",    "stopwords": "_english_" },
        "english_stemmer":            { "type": "stemmer", "language": "english" },
        "english_possessive_stemmer": { "type": "stemmer", "language": "possessive_english" }
      }
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

      "text": {                                 // BM25 field
        "type": "text", "analyzer": "psa_text",
        "fields": { "raw": { "type": "keyword", "ignore_above": 256 } }
      },
      "title": {
        "type": "text", "analyzer": "psa_text",
        "fields": { "raw": { "type": "keyword", "ignore_above": 256 } }
      },

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

      "ordinal":    { "type": "integer" },
      "tokenCount": { "type": "integer" },   // ~length/4 estimate; for future token-based budgeting
      "uri":       { "type": "keyword" },    // for citations
      "metadata": {                          // dynamic — connector/parser-supplied facets
        "type": "object",
        "properties": { /* explicitly typed sub-fields — see below */ }
      },
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

`DefaultSearchService` over-fetches on each leg before fusion: `size` and knn `k` are both
`topK × app.search.candidate-multiplier` (4 by default). A chunk that only one leg ranks well has to
survive long enough to reach the fusion step, so a multiplier of 1 would make hybrid mode pointless.
`topK` itself is clamped to `app.search.max-top-k` in the service — it is multiplied before it becomes
`size`, so an unbounded `topK` is an unbounded request to the cluster.

---

## What comes back on a hit

Two request-shaping details on both legs, and one distinction that has already caused a real bug:

- **`_source` excludes `embedding`.** Nothing on the read path reads the vector back, so without this
  every hit ships its full 768 floats to be parsed and dropped — on a 40-candidate hybrid search that
  is two orders of magnitude more bytes than the text the caller wanted.
- **`highlight` on `text` + `title`, lexical leg only** (`app.search.highlight-fragments`, 0 to
  disable). The `<em>` markers are stripped when the fragments are joined: the fragment *boundaries*
  are the useful part, and passing markup on would force the UI to render server-supplied HTML. The
  knn leg carries no query terms, so asking it for fragments would return none.
- **`text` vs `snippet` on `SearchHit`.** `text` is the chunk exactly as indexed and is what grounding
  is built from. `snippet` is a display excerpt — the highlight fragment where there is one, otherwise
  the first `app.search.snippet-chars` characters (280 by default; 0 disables truncation). Only
  `snippet` goes on the wire, since the console renders it and nothing renders the full text.

  This split is the fix for a shipped bug, and the reason it is documented here. The 280-char
  truncation used to be applied in the adapter with no way to recover the rest, and `DefaultSearchAgent`
  built its prompt from the snippet — so grounded answers were produced from roughly a quarter of every
  chunk. On a table the visible symptom was an answer that listed the first few rows, cut off mid-item,
  and then stated that the remaining rows were not present in the sources. Any new consumer of
  `SearchHit` has to pick a side of that line deliberately: display takes `snippet`, reasoning takes
  `text`.

---

## Filtering & permissions

Every query carries a `filter` on `knowledgeId` (and later, allowed-scope ids).

> **This is scoping, not access control.** The app has no authentication or authorization of any kind
> today (see [`limitations.md`](./limitations.md) and `ROADMAP.md`), so the `knowledgeId` filter only
> restricts a query to what the *caller asked for* — it does not restrict what a caller is *allowed*
> to ask for. Retrieval-time enforcement is the intended shape once identity exists; it is not a
> property the system has now.

`SearchQuery.filters` is a free-form `field → value` map with the key used **verbatim as the field
name**. That means callers can filter on any indexed field — top-level keywords like `sourceType`,
`iterableId`, or `uri`, or a dotted path into `metadata` — without a code change.

The *value* decides the clause:

| Value | Clause | Example |
|---|---|---|
| a scalar | `term` | `{"metadata.company": "Acme"}` |
| a map with `gte`/`gt`/`lte`/`lt` | `range` | `{"metadata.postedAt": {"gte": "2026-08-18T00:00:00Z"}}` |

A scalar cannot express "newer than" or "at least", which ruled out every date window and numeric
floor — hence the range form. Two details are load-bearing:

- **JSON types are preserved.** Values used to go through `String.valueOf`, which made
  `{"metadata.remote": true}` serialise as the string `"true"` — a term query against a `boolean`
  field then matches nothing, and a range bound compares lexicographically, where `"9" > "150000"`.
- **Unrecognised keys inside a bounds map are dropped, not forwarded.** Passing them through would let
  a caller inject query DSL through what is documented as a value. A map with no recognised bound
  degrades to a term that matches nothing, which is visible in the query rather than silently widening
  the result set.

### Collapsing duplicates

Opt-in per request (`collapseDuplicates`), applied **after** retrieval and **before** the topK trim, so
a collapsed result set is still full. It is a grouping over the returned hits — nothing is deleted and
the index is never touched. Three layers: identical normalised text, an equal `metadata.dedupeKey`, then
token-shingle overlap above `app.search.dedupe.near-duplicate-threshold`.

> **Shingles rather than embedding cosine, deliberately.** Cosine answers a different question: two
> genuinely distinct roles at one company, or two reports on one project, sit very close in embedding
> space, and collapsing them would hide a real result — a worse failure than showing a duplicate.
> Shingle overlap measures shared *wording*, which is what "the same document twice" actually is. It is
> also free, since hits already carry their text while the vectors are excluded from `_source`.

### Searching by a document

`SearchQuery.sourceEntityId` searches *by* an already-ingested document — "find everything like this" —
instead of by typed text. The document is **not** used as the query. Doing that fails twice over: the
lexical leg degenerates, because `minimum_should_match` tuned for a sentence behaves unpredictably
across several hundred words and every incidental term becomes scoreable; and the vector leg degenerates
differently, because one embedding of a long document is a centroid near nothing in particular, and the
match is *asymmetric* — a CV and a job posting describe the same work in different registers.

Instead `DocumentQueryPlanner` asks the `document-facets` task for a handful of short queries written in
the register of the corpus being searched, and `FacetedRetrieval` runs each as an ordinary query and
fuses the rankings with RRF. Nothing about the retrieval path is special-cased; fusion is what makes it
more than a concatenation, since a document matching three facets outranks one matching a single facet
very well.

- Facets are cached by the source entity's **checksum**, already the corpus-wide change signal
  (invariant 3) — edit the document and the key moves on its own, with nothing to invalidate.
- An unavailable model or an unreadable reply falls back to the document's opening text as a single
  query. A degraded model must not become a broken feature; the fallback is deliberately *not* cached.
- **A file-backed entity is read from its indexed chunks.** `LOCAL_FS` — the obvious place to keep a CV
  — always stores a `fileRef` and never inline text, so the source document is reassembled from
  `SearchIndex.chunkTextsByEntity` (bounded by `app.search.document-query.max-chunks`) whenever the
  entity carries none. Re-parsing the file on the read path would duplicate the indexing stage and would
  fail for a source whose bytes are no longer local. An entity with neither inline text nor chunks is a
  400 saying to index it first.
- A document past `app.search.document-query.max-chars` is **rejected** with a 400 rather than
  truncated: facets drawn from whichever pages came first would look like a working search returning bad
  results.
- `app.search.document-query.max-facets` caps the fan-out, so a model's verbosity cannot decide how
  expensive a search is.

`SearchQuery.maxChunksPerEntity` overrides `app.search.max-chunks-per-entity` per request. Setting it
to `1` gives one result per document — the right shape for a record-like corpus (a job posting) without
forcing whole-document chunking, which would exceed the embedding provider's input limit on a long
description.

---

## Operational notes

- **Alias indirection**: app reads/writes `chunks`; physical index is `chunks_v3_768`.
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
