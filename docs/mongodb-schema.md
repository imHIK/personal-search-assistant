# MongoDB Schema — source of truth

MongoDB holds the **canonical** records. OpenSearch is derived from these and can be
rebuilt at any time by replaying Mongo. Database: `personal_assistant`.

Design goals: easy incremental sync, full reprocessing from source of truth, and clean
support for many heterogeneous sources without schema churn.

> **Note:** the ingestion/indexing model has since evolved — see `indexing-design.md`, which
> is authoritative. There: `sources` → **`knowledge`**, `documents` → **`entities`**, and
> cursors become a **first-class `cursors` collection** (instead of the embedded `sync`
> block shown below). **Chunks are not a Mongo collection at all** — they live only in
> OpenSearch. The sections below still hold for field-level detail; read the names per the
> new model.

---

## Collection: `sources`
One document per connected source (a folder, a mailbox, a workspace).

```jsonc
{
  "_id": "src_local_documents",        // stable, human-readable id
  "type": "LOCAL_FS",                   // SourceType enum
  "name": "My Documents",
  "config": { "rootPath": "/home/me/Documents" },  // connector-specific, opaque to core
  "status": "ACTIVE",                   // ACTIVE | PAUSED | ERROR
  "sync": {
    "cursor": "2026-06-20T10:00:00Z",   // opaque watermark for incremental sync
    "lastRunAt": "2026-06-20T10:00:00Z",
    "lastStatus": "OK",
    "stats": { "documents": 1240, "failed": 3 }
  },
  "createdAt": "...",
  "updatedAt": "..."
}
```

Indexes: `{ type: 1 }`, `{ status: 1 }`.

> `config` is intentionally a free-form sub-document. Each `SourceConnector` reads its own
> keys; the core never inspects it. This is the seam that lets new source types arrive
> without schema migrations.

---

## Collection: `documents`
One document per ingested item (a file, an email, a message).

```jsonc
{
  "_id": "doc_8f3a...",
  "sourceId": "src_local_documents",
  "externalId": "/home/me/Documents/report.pdf",  // natural key within the source
  "contentType": "application/pdf",                // drives parser selection
  "title": "Q2 Report",
  "uri": "file:///home/me/Documents/report.pdf",   // for citation / open-in-source
  "text": "…extracted plain text…",                // parser output (may be large/omitted)
  "metadata": {                                    // normalized + source-specific
    "author": "…", "createdAt": "…", "modifiedAt": "…",
    "labels": ["work"], "sizeBytes": 24576
  },
  "checksum": "sha256:…",        // change detection — skip re-index if unchanged
  "index": {
    "status": "INDEXED",         // PENDING | PARSING | CHUNKED | INDEXED | FAILED
    "chunkCount": 12,
    "error": null,
    "indexedAt": "…"
  },
  "createdAt": "…",
  "updatedAt": "…"
}
```

Indexes:
- `{ sourceId: 1, externalId: 1 }` **unique** — dedup / upsert key per source.
- `{ "index.status": 1 }` — find work to (re)process.
- `{ checksum: 1 }` — cross-source dedup (optional).
- `{ updatedAt: -1 }`.

> Storing `text` in Mongo is optional. For large corpora, keep only metadata + a pointer
> and stream text from the source on demand. Start simple (store it), revisit if it grows.

---

## Chunks — NOT a Mongo collection

Chunks are **not stored in Mongo**. They are a derived artifact produced at indexing time
and written **only to OpenSearch** (see `indexing-design.md` §8 and `opensearch-index.md`).
The entity's retained content/`fileRef` is the source of truth, so chunks can always be
regenerated. The entity records only metadata *about* its chunks: `chunkCount`,
`embeddingModel`, `indexedAt`.

---

## Lifecycle & sync notes

- **Upsert by `(sourceId, externalId)`**; compare `checksum` to decide skip vs re-index.
- **Deletes**: when a connector reports an item gone, soft-delete the entity, then delete
  its chunks from **OpenSearch** (chunks are not in Mongo).
- **Reprocessing**: anything in `index.status = FAILED` or `PENDING` is the work queue.
- **Idempotency**: chunk ids are derived (`documentId + ordinal`) so re-runs overwrite
  cleanly rather than duplicating.

> Collections map to repository ports (`SourceRepository`/`DocumentRepository` — renamed to
> Knowledge/Entity repos in the new model) so swapping Mongo for another store later touches
> only `storage.mongo`. There is **no chunk repository** — chunks live in OpenSearch.
