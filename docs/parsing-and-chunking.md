# Parsing & Chunking — design

How raw bytes become good, retrievable text: **per-file-type extraction** so each format is parsed
the best way, and **pluggable, per-knowledge chunking** so each knowledge can split that text the way
that suits its content. Both slot into the existing ports (`ContentParser`, `ChunkingStrategy`) from
[`ARCHITECTURE.md`](../ARCHITECTURE.md) — no changes to the indexing pipeline's shape.

Read alongside [`indexing-implementation.md`](./indexing-implementation.md) (the indexing stage this
sits in) and [`knowledge-edit-design.md`](./knowledge-edit-design.md) (why a chunking change is a
config-class edit).

---

## 1. Parsing — one best-fit parser per file family

`IndexingRunner.extractText` asks the `ParserRegistry` for a `ContentParser` by MIME type. Selection
is by `priority()` (lower wins), so a **specific** parser is always preferred over the general
fallback. Previously everything non-plain-text funnelled through a single generic `Tika.parseToString`;
now each common family has a dedicated parser configured for *that* format, which extracts more
meaningful text (reading order, notes, stripped markup) than blind sniffing.

| Parser | Claims (MIME) | Priority | Why it's tuned this way |
|---|---|---|---|
| `PlainTextParser` | `text/*` (incl. Markdown), JSON, XML, YAML, CSV/TSV — **not** HTML | 0 | Verbatim UTF-8 *is* the best strategy for text/code; there is no markup to strip. |
| `PdfContentParser` | `application/pdf` | 10 | PDFBox with sort-by-position (multi-column reading order), duplicate-text suppression, images off (no OCR). |
| `WordDocumentParser` | `.doc`, `.docx`, `.odt` | 10 | POI with headers/footers, phonetic runs de-duplicated → clean paragraph/heading/table text. |
| `PresentationParser` | `.ppt`, `.pptx`, `.odp` | 10 | Includes **speaker notes** as well as slide text — often where the substance is. |
| `SpreadsheetContentParser` | `.xls`, `.xlsx`, `.ods` | 10 | Each sheet's cells emitted row by row under the sheet name. |
| `HtmlContentParser` | `text/html`, `application/xhtml+xml` | 10 | Drops `<script>`/`<style>`/markup, keeps visible text in order. |
| `TikaContentParser` | everything else (fallback) | 100 | Long tail — RTF, EPUB, mail containers, unknown binaries — so extraction never hard-fails. |

All extraction goes through one helper, `TikaSupport`, which runs Tika into a body handler, salvages
partial text if a document exceeds the 10M-char safety cap, and harvests a little metadata
(`docTitle`, `docAuthor`, `pageCount`). Scope is **digital text only — no OCR**: scanned PDFs/images
are out of scope for now and would be added later as a higher-priority OCR `ContentParser` (plus a
native Tesseract dependency) without touching any caller.

> **Note on HTML.** HTML is `text/*` but carries markup, so `PlainTextParser` explicitly *excludes*
> it and `HtmlContentParser` (higher priority number, but the only non-fallback that claims it) wins.

### Adding a file type

Implement `ContentParser` (claim your MIME types in `supports`, set a `priority()` below 100),
annotate `@ApplicationScoped`, and — if it's a Tika format — delegate to `TikaSupport.extract` with a
tuned `ParseContext`. Nothing else changes; `CdiParserRegistry` discovers it automatically.

---

## 2. Chunking — named strategies, chosen per knowledge

### 2.1 Strategies

`ChunkingStrategy` now takes a `ChunkingSpec` (size, overlap, separators) so one stateless bean serves
every knowledge. Four widely-used strategies ship, each registered as a CDI bean and selectable by
`name()`:

| `name` | Strategy | Size unit | Behaviour |
|---|---|---|---|
| `recursive` | `RecursiveCharacterChunkingStrategy` | characters | Splits on a separator hierarchy (paragraph → line → sentence → clause → word → char), descending only for fragments still over size, then merges with overlap. Keeps semantic units intact — the common RAG default. |
| `character` | `CharacterChunkingStrategy` | characters | Splits on a single separator (blank line by default), merges to size with overlap; hard-windows any still-oversized fragment as a safety net. |
| `fixed-size` | `FixedSizeChunkingStrategy` | characters | Blind sliding character window with overlap. Simplest, dependency-free. |
| `token` | `TokenChunkingStrategy` | tokens | Sizes windows in real tokens (HuggingFace tokenizer, via DJL) so chunks fit the embedding model's context; chunk text stays a verbatim substring. Falls back to a ~4-chars/token approximation if the tokenizer can't load. |

The `recursive`/`character` merge follows the well-known LangChain algorithm, so behaviour matches what
users expect from that ecosystem. Chunk assembly (stable `entityId_ordinal` ids, denormalized
title/uri, carried entity facets) is shared in `ChunkSupport`, so every strategy emits identical chunk
shapes.

### 2.2 Selection & resolution

`IndexingRunner` resolves the strategy **per entity, per pass**:

```
ChunkingSpec spec = chunkingSpecs.resolve(knowledge);   // knowledge settings over global defaults
ChunkingStrategy strategy = chunking.get(spec.strategy()); // registry; unknown/unset → default
strategy.chunk(entity, sourceType, text, spec);
```

`ChunkingSpecResolver` overlays the knowledge's `config.chunking` (each field nullable = "inherit")
onto the global `app.chunking.*` defaults. Character strategies default to the character size/overlap;
`token` defaults to the smaller token size/overlap, so switching a knowledge to `token` yields sensible
windows without also resizing. `CdiChunkingStrategyRegistry` never hard-fails: an unknown or unset
strategy name degrades to the configured default (`app.chunking.strategy`, default `recursive`).

### 2.3 Direct-update semantics (past chunks stay)

Chunking is chosen per knowledge and is a **config-class edit** (see
[`knowledge-edit-design.md`](./knowledge-edit-design.md) §2): a single in-place write, **no**
re-verify/re-discover, **no** cursor disruption, and crucially **no re-chunk of already-indexed
entities**. Because `IndexingRunner` reads the current settings every time it indexes an entity, the
change takes effect on the *next* entity indexed (new, updated, or explicitly re-indexed) while chunks
already in OpenSearch are left exactly as they were. This is the "direct update" contract: future
chunks change, past chunks don't.

> Want to also re-chunk existing data? That's deliberately opt-in via the existing re-index endpoint
> (`POST /api/index/entities/{id}/reindex`), which re-runs this same path with the new settings.

### 2.4 API

Set chunking when creating or editing a knowledge; any field omitted inherits the global default.

```jsonc
// POST /api/knowledge   (create)   or   PATCH /api/knowledge/{id}   (edit — applied in place)
{
  "chunkingStrategy": "recursive",     // recursive | character | fixed-size | token
  "chunkingMaxSize": 1000,             // characters, or tokens for the token strategy
  "chunkingOverlap": 150,
  "chunkingSeparators": ["\n\n", "\n", " "]   // recursive/character only; omit to use defaults
}
```

The current settings are visible on `GET /api/knowledge/{id}` under `config.chunking`.

### Adding a chunking strategy

Implement `ChunkingStrategy` (unique `name()`, split in `chunk(...)` honouring the `ChunkingSpec`),
annotate `@ApplicationScoped`, and reuse `ChunkSupport.toChunks` / `TextSplitters` for assembly and
splitting. `CdiChunkingStrategyRegistry` picks it up; a knowledge selects it by name.

---

## 3. Config reference

| Key | Default | Meaning |
|---|---|---|
| `app.chunking.strategy` | `recursive` | Default strategy for a knowledge that hasn't customised chunking. |
| `app.chunking.size` / `.overlap` | `1000` / `150` | Character size/overlap for `recursive` / `character` / `fixed-size`. |
| `app.chunking.token.size` / `.overlap` | `256` / `32` | Token size/overlap for the `token` strategy. |
| `app.chunking.token.tokenizer` | `bert-base-uncased` | HuggingFace tokenizer id used to measure tokens (lazy, with a fallback). |
```
