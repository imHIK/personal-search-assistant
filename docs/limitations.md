# Known Limitations

A running list of accepted trade-offs and known gaps. These are deliberately *not* fixed yet — we'll
review them together and decide the best approach once the list is more complete. Each entry records
the limitation, why it exists today, its impact, and candidate fixes.

---

## L1 — Pause/resume park-vs-rearm race

**Area:** Ingestion · cursor lifecycle (`DefaultKnowledgeService.pause/resume`, `IngestionJob.tryRun`)

**What:** Pausing a knowledge parks its claimable cursors (`AVAILABLE/IDLE → SUSPENDED`) and resuming
re-arms them (`SUSPENDED → AVAILABLE`). The ingestion loop also parks paused-knowledge stragglers as
a backstop. There is a narrow interleaving where a cursor can end up stuck `SUSPENDED` while its
knowledge is `ACTIVE`:

1. A tick reads the cursor's knowledge and sees `PAUSED`.
2. The user calls `resume()` → knowledge flips `ACTIVE` and `resumeByKnowledge` re-arms the cursors.
3. The same tick (still mid-`tryRun`, acting on its stale read) calls `suspendByKnowledge` and parks
   the just-re-armed cursor again.

The cursor is now `SUSPENDED` under an `ACTIVE` knowledge and won't be picked up.

**Impact:** Low. Requires a pause→resume within the same poll interval and an unlucky overlap with an
in-flight tick. It is **not** permanent data loss — the next user `resume()`, a `triggerSync`, or any
operation that re-arms cursors clears it; only that one knowledge's incremental sync stalls until
then. No effect on already-indexed/searchable content.

**Why we left it:** The robust fixes add ongoing cost or complexity that isn't justified for a
low-probability, self-recoverable stall (see below). Deferred pending the full limitations review.

**Candidate approaches (for later):**
- *Periodic reconcile sweep* — a scheduled job re-arms `SUSPENDED` cursors whose knowledge is
  `ACTIVE`. Simple and also heals any other way a cursor could get orphaned in `SUSPENDED`, at the
  cost of one more background loop.
- *Status-fenced park* — make the in-loop `suspendByKnowledge` conditional on the knowledge still
  being non-active at write time (compare-and-set), so a concurrent resume wins. Tighter, but pushes
  more coordination into the hot path.
- *Re-check after claim* — re-read knowledge status immediately before parking. Shrinks the window
  but doesn't fully close it.
- *Version/epoch on knowledge* — tag park writes with the knowledge status version and reject stale
  ones. Most correct, most invasive.

---

## L2 — Edit cleanup is deferred (no purge on shrink)

**Area:** Knowledge editing · reconcile (`knowledge-edit-design.md` Phase 2)

**What:** Editing a knowledge (`PATCH /api/knowledge/{id}`) ships in two phases. **Phase 1 is
implemented** — it makes *additions* correct (re-verify, re-discover, and re-walk iterables whose
membership signature changed so newly-matching items get ingested) and records the staleness marks,
but performs **no deletion**. **Phase 2 (this limitation) is deferred.** Two kinds of now-stale data
are deliberately left in place:

1. **Parked iterables.** When `discover()` returns fewer iterables after an edit, the missing ones are
   retired (`RETIRED`) but their chunks/entities are **kept** (park-don't-purge), because the
   framework can't tell an intentional removal from an accidental scope/account drop.
2. **Narrowed-out items.** When a filter narrows within an iterable (e.g. drop `docx`), the
   no-longer-matching entities stay indexed. They are *marked* — `Knowledge.syncGeneration` is bumped
   and `Entity.lastSeenGeneration` is stamped on every walk — but not removed.

**Impact:** Medium. Stale results remain searchable until cleaned up. Not data loss; purely
over-inclusion. The marks mean no extra re-walk is needed later to identify what is stale.

**Why we left it:** Deletion on ambiguous shrink is unsafe, and we chose to record the staleness
signal now and build one deliberate cleanup path rather than scatter ad-hoc deletes.

**Candidate approach (Phase 2):** a single explicit/confirmed purge that (a) sweeps entities with
`lastSeenGeneration < syncGeneration` after a *completed* re-walk (completion-gated so a partial walk
can't mass-delete), and (b) removes parked (`RETIRED`) iterables' kept data.

---

## L2b — Source-side deletion is only handled by retention

**Area:** Ingestion · entity lifecycle

**What:** No connector emits a tombstone. `RawItem.tombstone` exists and `IngestionRunner` handles it,
but nothing produces one — so a local file that is deleted, a Drive file that is trashed, or a Gmail
message that is removed stays ingested and searchable indefinitely.

Entity **retention** (`config.retention.period`, `RetentionSweeper`) covers this for sources that opt
in: an item ages out on a window measured from `createdAt`, whether or not it still exists upstream.
That is the right answer for feeds — the ATS job boards use it — because a posting the source stopped
returning is stale regardless. It is *not* a general answer, for two reasons:

1. Retention is opt-in and unset by default, so document corpora get no cleanup at all. This is
   deliberate: a global window would silently start deleting Drive/Gmail/local content.
2. Ageing out is not the same as noticing a removal. An item deleted upstream lingers for the whole
   window, and an item that still exists is deleted and then **re-created** by the next walk —
   re-parsed, re-chunked and re-embedded, with a fresh `createdAt`. Windows must therefore be long
   enough that anything surviving one is genuinely stale.

**Impact:** Low–medium. Over-inclusion, never data loss.

**Candidate approach:** a `lastSeenAt` stamped on every walk including the change-detection skip path
(`stampLastSeen` already fires there, so it is one extra field on an existing write), which would
distinguish *removed at source* from *merely old* and let a closed item be tombstoned on the next poll
instead of at the end of the window. Gmail's push (`watch`) and Drive's Changes feed are the
source-specific alternatives.

---

## L3 — No OCR, and no table structure in formats that do not carry it

> **Largely superseded.** Two earlier forms of this limitation are now closed. The per-format parser
> layer was built, and structure-preserving extraction has since replaced the flattening body handler:
> `StructureAwareHandler` turns Tika's XHTML back into row-per-line text and records
> `ParsedContent.blocks`, which the `table` chunking strategy splits on while repeating the header row
> into every chunk. See [`parsing-and-chunking.md`](./parsing-and-chunking.md) §1.1. What remains is
> narrower.

**Area:** Indexing · content parsing (`indexing/parser/*` — `ContentParser`, `CdiParserRegistry`,
`TikaSupport`, `StructureAwareHandler`)

**What:** Seven parsers ship and are selected by MIME type through `CdiParserRegistry`, lowest
`priority()` first: `PlainTextParser` (0), then `PdfContentParser` / `WordDocumentParser` /
`SpreadsheetContentParser` / `PresentationParser` / `HtmlContentParser` (all 10), with
`TikaContentParser` (100) as the catch-all fallback. Two gaps remain:

1. **No OCR.** Scanned PDFs and images extract to little or nothing — Tesseract is not wired into
   Tika, so image-only content is effectively unsearchable.
2. **A format that does not encode tables still has none.** Structure now survives extraction *where
   the format carries it* — spreadsheets, Word tables, HTML. A PDF carries no table semantics: a
   visually tabular page is positioned text, which PDFBox emits as a sequence of `<p>`. So a PDF
   yields paragraph blocks and no table blocks, and while rows are no longer cut in half, there is no
   header row to identify or repeat. The `table` strategy correctly falls back to recursive splitting.

**Impact:** Low–Medium. Scanned documents are silently near-empty rather than failing loudly. For
tabular PDFs, chunks after the first hold bare rows with no column headers and nothing naming the
table, which is a retrieval problem rather than an extraction one — see **L6**, and note that the
embedded context prefix (the `embedContext` field set in `config/field-sets.json`) is what addresses it, not extraction.

**Why we left it:** OCR adds a heavyweight native dependency for a format uncommon in typical personal
corpora. Recovering table structure from a PDF's visual layout is a genuinely hard inference problem
(column detection from glyph positions), quite unlike reading markup that already says `<tr>`.

**Candidate approaches (for later):** wire Tesseract in as a higher-priority image/PDF
`ContentParser` — the CDI registry auto-discovers it and prefers it over the fallback with no wiring
changes; and, for tabular PDFs, either a layout-analysis pass that infers columns from glyph geometry
(Tika can expose positions) or a heuristic that detects a repeated line shape and treats the first
matching line as a header.

---

## L4 — Large files can strain or stall indexing

**Area:** Indexing · parse/embed of large content (`IndexingRunner`, `TikaContentParser`) and connector
download paths (e.g. `GoogleDriveConnector` `maxFileBytes`)

**What:** Large files are handled naively. Connectors download the whole file into a `byte[]` and write
it to scratch, and Tika parses it in memory. Very large files can cause memory pressure, slow parses
that risk exceeding the per-entity lease (`app.indexing.lease-seconds`) or the indexing permit TTL
(`app.indexing.permits.ttl-seconds`), and a large chunk/embedding fan-out. The only guard today is a
blunt, connector-level `maxFileBytes` cap (Drive) that *silently skips* anything over the limit.

**Impact:** Medium. Large-but-under-cap files can slow a tick or hold a lease long enough to be
reclaimed and retried; oversized files are skipped and never indexed, with no signal surfaced to the
user beyond a recorded `sizeBytes`.

**Why we left it:** Uncommon in typical corpora, and the recent lease/permit-TTL bump buys headroom.
Deferred pending the review.

**Candidate approaches (for later):** streaming/bounded parsing instead of whole-file in memory; a
global max-content-bytes with an explicit "skipped: too large" entity status surfaced to the user
rather than a silent skip; chunk-count caps; size-aware batching so one huge file can't dominate a tick.

---

## L5 — Background schedulers wake on fixed intervals, not each knowledge's configured schedule

**Area:** Ingestion · background scheduling (`IterableDiscoveryScheduler.tick`,
`ForwardCursorScheduler.tick`, `ScheduleResolver`, `app.scheduler.discovery-interval` /
`app.scheduler.forward-interval`)

**What:** Two periodic schedulers wake on fixed `@Scheduled(every=…)` intervals rather than being
driven by each knowledge's resolved `SyncSchedule`. They differ in how much that matters:

1. **Iterable discovery ignores the schedule entirely.** `IterableDiscoveryScheduler` fires every
   `app.scheduler.discovery-interval` (currently `60m`), iterates *every* `ACTIVE` knowledge, and
   reconciles cursors for sources with dynamic iterables (`hasDynamicIterables()` — e.g. new child
   folders / Slack channels / Drive folders). It never consults the knowledge's `SyncSchedule`, so
   newly-appeared sub-streams are discovered on a flat 60-minute cadence **irrespective of the
   configured schedule** — a knowledge set to sync every 5 minutes still won't pick up a new
   folder/channel until up to an hour later.
2. **Forward re-arm honors the schedule; only its check granularity is fixed.**
   `ForwardCursorScheduler` fires every `app.scheduler.forward-interval` (currently `1m`), but that
   tick is only the *check* cadence: each tick arms only knowledges whose `nextSyncDueAt` has arrived
   and rolls that due time forward by the resolved schedule. The fixed interval therefore just bounds
   how promptly a due sync is picked up (≤ one tick), not the effective cadence.

**Impact:** Low–Medium. For discovery, a brand-new sub-stream can lag its first sync by up to one
`discovery-interval` (60m) no matter how frequently the knowledge is configured to sync; already-known
cursors are unaffected. For re-arm, incremental sync can lag its due time by up to one
`forward-interval` (1m). Neither is data loss and both self-correct on the next tick — freshness is
just capped by the check granularity rather than by the user's schedule.

**Why we left it:** Both fixed intervals are fine for the day-scale default schedules in normal use, so
the mismatch is invisible; a growing sub-stream set is also uncommon enough that hourly discovery is
acceptable. Deferred pending the review.

**Candidate approaches (for later):** drive the discovery cadence per-knowledge from the resolved
schedule (or fold reconcile into the same due-check the forward scheduler already runs) instead of a
flat global interval; for both schedulers, wake at the earliest upcoming `nextSyncDueAt` rather than a
fixed `every=…`; or, minimally, document `discovery-interval` / `forward-interval` as the floor on
discovery/sync latency and validate configured schedules against them.

---

## L6 — Retrieval has no way to recover content that spans chunk boundaries

**Area:** Read path · retrieval + answering (`HybridRetriever`, `NoopReranker`,
`DefaultSearchAgent`, `AnswerPromptBuilder`)

**What:** A hit is a single chunk, and nothing on the read path can reach the chunks around it. If an
answer needs content that spans several chunks of one document, each of those chunks has to earn its
own place in the top-K independently — there is no neighbour expansion, no whole-entity escalation, and
no second retrieval round.

That is fine for prose, where a paragraph is usually self-contained, and it is a real gap for lists and
tables. The first chunk of a table carries the header row and matches the query; the continuation
chunks are bare rows that match neither the keywords nor the query vector, so they never appear as
hits, and the answer is built from the fraction of the list that happened to rank. This is the
retrieval half of the same failure recorded in L3 — the extraction half is what makes those
continuation chunks context-free in the first place.

Two smaller read-path gaps sit alongside it:

1. **No reranker.** `NoopReranker` only trims to `topK`, so final ordering is whatever RRF produced.
   Precision on near-miss candidates is untuned. Tracked in `ROADMAP.md`.
2. **Queries and documents are embedded identically.** All three `EmbeddingProvider`s call the same
   path for both, while the configured models (`gemini-embedding-001`, `bge-base-en-v1.5`) are
   asymmetric-trained and document a query-side instruction or `task_type`. Retrieval works, but the
   vector leg is not being used the way the model was trained.

**Impact:** Medium for list/table content — an answer can be confidently incomplete, which is worse
than an obvious failure, because the model reports the rows it cannot see as absent from the source
rather than as unretrieved. Low for prose. No data is lost or corrupted; everything is in the index and
a differently-phrased query can reach it.

**Why we left it:** post-hoc expansion is a guess. Pulling in `ordinal ± n` spends the context budget
on chunks that may be irrelevant, and adjacency is a poor proxy for relevance. The better fix is
upstream — extraction that keeps table rows intact and chunking that repeats the header row, so each
chunk is independently retrievable on its own merit (see L3's candidate fix). Beyond that, expansion
belongs to an agent that can *decide* to expand, with `expand_chunk` / `fetch_entity` /
`list_entity_chunks` as tools, rather than to a fixed heuristic in the retrieval path. `SearchHit`
already carries `ordinal` so those tools have what they need.

**Candidate approaches (for later):** structure-preserving extraction plus a header-repeating,
row-aligned chunking strategy (the upstream fix, L3); a real `Reranker` behind the existing port
(LLM listwise, or an ONNX cross-encoder on the DJL runtime already wired up); `embedQuery` as a
default method on `EmbeddingProvider` so asymmetric models get their query instruction; and an agentic
retrieval loop that exposes expansion as tools it can choose to call.

---

## L7 — Duplicate collapsing sees only the retrieved page

**Area:** Retrieval · `DuplicateCollapser`

**What:** Collapsing runs over the candidates one query returned, not over the corpus. Two copies of
the same document only group if both are retrieved for that query — if one ranks below the candidate
window, the other is shown alone and nothing indicates a duplicate exists. The near-duplicate layer is
additionally capped at `app.search.dedupe.max-comparisons`, because it is pairwise.

It is also text-based (token shingles), so it is length-sensitive: a fixed boilerplate header costs
proportionally more overlap on a short chunk than a long one, which is why the threshold sits at 0.85
rather than higher.

**Impact:** Low. Over-inclusion — a duplicate slips through — never a missing distinct result, which is
the failure the thresholds are tuned to avoid.

**Why we left it:** corpus-wide dedupe means a clustering pass over every chunk, with somewhere to
store the cluster assignments and a way to keep them fresh as content changes. That is a real feature,
not a tweak, and query-time grouping covers the case that actually shows up in a result list.

---

## L8 — Digest run history is unbounded

**Area:** Digests · `digestRuns`

**What:** Every run is kept forever. Nothing prunes the collection, so a daily digest accumulates a
run document per day indefinitely.

The newness check is now bounded below by the digest's `historyResetAt` when one is set
(`reportedEntityIds(digestId, since)`), and the `(digestId, ranAt)` index covers that read — so the
worse half of this, a check that slows as the digest ages, is relieved for any digest whose history
has been reset. A digest that has never been reset still reads its whole history, and nothing prunes
`digestRuns` either way.

The read is projected to entity ids alone, so this is a slow drift rather than a cliff: a year of daily
runs at topK 10 is ~3,650 ids. It will not be felt soon; it will be felt eventually.

**Impact:** Low, and slow.

**Why we left it:** any retention rule here is a real product decision — capping runs discards history a
user may want, while capping the already-seen set silently starts re-reporting old items. Both need a
deliberate choice rather than a default picked to keep a collection small.

**Candidate approach:** keep the last N runs for display, plus a separate compacted "seen" set per
digest that is appended to rather than recomputed.

---

## L9 — Rate-limit counters are per-process and lost on restart

**Area:** Outbound rate limiting · `common.ratelimit.SlidingWindowRateLimiter`

**What:** The rolling windows and the `Retry-After` pauses live in a `ConcurrentHashMap` inside one
`@ApplicationScoped` bean. Two consequences follow. A restart begins with every window empty, so the
instant after a restart the app may send one full burst beyond what the window should have allowed.
And a second node would keep its own counters, so an *n*-node deployment enforces roughly *n* times the
configured rate.

The *policy* is not affected — that is stored on the connection in Mongo, or in `application.properties`
— only the counters are.

**Impact:** Low today, and deliberately so. This is the same single-node assumption `InMemoryPermitService`
already makes (invariant 7), and the deployment is single-node; a one-off burst after a restart is well
inside what any published quota tolerates. It becomes real the moment a second replica is added, which
would also break permits.

**Why we left it:** persisting a counter that changes on every outbound call would put a write on the hot
path of every request, to protect against an event (restart) that costs at most one window's worth of
allowance. The `RateLimiter` port exists precisely so this can be swapped without touching a caller.

**Candidate approach:** a Redis-backed `RateLimiter` (a Lua sorted-set window, or `INCR` plus `EXPIRE`
if the fixed-window approximation is acceptable), introduced at the same time as the Redis `PermitService` the concurrency side already
anticipates — they share the same trigger and should not be done separately.

---

## L10 — Nothing measures or caps what a task costs

**Area:** Tasks · digests (`DefaultDigestService`, `TaskLibrary`)

**What:** A digest's task runs on every scheduled run, over up to `maxSources` results, with no
accounting. The console warns that this is a recurring cost and `llmProfile` is offered per task so a
cheap model can be chosen, but nothing records spend, caps it, or notices that a task got expensive.
A daily digest over ten whole documents is a daily bill nobody is shown.

This mattered less when the only task was `job-fit` on the `lite` profile. It matters more now that
tasks are user-written and a digest can be pointed at any of them.

**Impact:** Low today, and grows with use.

**Workaround:** Pause the digest, lower `maxSources`, or point the task at a cheaper profile.

---

## L11 — A staged file outlives nothing; losing it dead-letters the entity

> **Closed.** Re-index now goes back to the source for content whose stored reference is a staged
> copy. What follows records the problem and the shape of the fix; the behaviour is documented in
> [`indexing-implementation.md`](./indexing-implementation.md) §5 and [`connectors.md`](./connectors.md) §6.

**Area:** Ingestion · content staging (`GoogleDriveConnector.materialize`) and re-index
(`IndexingRunner.extract`, `DefaultIndexingService`)

**What it was:** A binary Drive file is stored on the entity as `content.fileRef` — a *path* into the
scratch dir, which defaults to `${java.io.tmpdir}/psa-drive`. `IndexingRunner.extract` re-opens that
path on every index pass, because a re-index was source-free by design. So bytes fetched once at
ingestion time had to survive indefinitely, in a directory the OS is entitled to empty: macOS purges
per-user `$TMPDIR` entries untouched for ~3 days, and any restart on ephemeral storage does the same.

When they were gone, indexing threw `IllegalStateException: Failed to read file …`, was retried
`app.indexing.retry-limit` times and then dead-lettered — an error naming a temp path, on an entity
whose source had not changed. It could not heal itself: ingestion *would* re-fetch (a `FAILED` entity
falls through the checksum skip), but the forward cursor's high-water floor means an unmodified file
is never re-listed, so no walk ever reached it. Because `/reindex` is also how existing entities opt
into a chunking or embedding-model change (invariant 6), the first such change dead-lettered every
staged file in the knowledge at once.

**The fix.** A connector declares a `ReindexMode` — `REINDEX_ONLY` when its stored content is durable
(inline text in Mongo, a `LOCAL_FS` path to the user's own file), `FETCH_AND_REINDEX` when it is a
staged copy (`GOOGLE_DRIVE`). Re-index reads that declaration, so the caller still asks only for "make
this current again"; `app.indexing.refetch-on-reindex` (`auto` / `always` / `never`) overrides it.

Two routes, because the two scales want different machinery:

- **One entity** — `SourceConnector.fetchOne(knowledge, entity)` re-lists the item by its external id
  and the result is materialized and upserted synchronously. A walk pages through a source and cannot
  be asked for one known id, so this is the only way to reach a single entity. Implemented for
  `GOOGLE_DRIVE` (`files.get`), `LOCAL_FS` (a re-stat) and `GMAIL` (`messages.get`); each reuses the
  connector's own listing-row mapping, so the re-listed item is what a walk would have produced —
  including the checksum, which is what stops the next poll seeing a phantom change. Empty means gone
  at the source, and the entity is tombstoned.
- **A whole knowledge** — `Entity.needsRefetch` is flagged on every file-backed entity and the
  knowledge's cursors are rewound, so the ordinary ingestion walk refreshes them inside the lease,
  permit and rate-limit machinery it already has. The flag is the escape from the checksum skip
  (invariant 3): unchanged at the source is exactly the case it has to override. Flag before rewind,
  or a cursor that starts walking first re-skips the items. `RETIRED` cursors are left parked, and a
  `BACKWARD` cursor is rewound only when backfill is still enabled or it has already `EXHAUSTED`.

The membership re-walk of a knowledge edit takes the same flag: it is the one moment the whole corpus
passes back through ingestion, so it is also the cheapest chance to refresh staged copies.

**And the dead letter heals.** `IndexingRunner` now distinguishes a missing file (`NoSuchFileException`)
from an unreadable one. A missing staged copy is terminal on the first attempt — no backoff can make
the file reappear, so the ladder only delays an actionable signal by `retry-limit × backoff` — and the
same write sets `needsRefetch`, so the next walk that re-lists the item re-materializes it.

**Residual:** a knowledge re-index of a Drive corpus re-downloads every staged file, which is real API
and time cost; it is logged, and `refetch-on-reindex=never` opts out. Entities whose iterable is
`RETIRED` are not reached by the walk (their cursors stay parked, per L2) and must be re-indexed
individually. Setting `app.ingestion.google-drive.download-dir` to a durable directory still avoids
the purge, at the price of an unbounded, uncollected on-disk dependency per file; parsing at ingestion
and storing the extracted text remains the other alternative, and still drops `ParsedContent.blocks`.

---

## L12 — In-flight OAuth consents are held in memory on one node

**Area:** OAuth connect flow · `ingestion.connector.oauth.OAuthStateStore`

**What:** The `state` token that ties an OAuth callback back to the consent that started it — along
with the connector type, the target connection and the redirect URI — lives in a `ConcurrentHashMap`
inside one `@ApplicationScoped` bean, with a `app.oauth.state-ttl-seconds` (600) expiry. A restart
between "Connect" and the provider's redirect loses it, and the callback lands on
`/connections?oauth=error` saying the link has expired. A second node would reject any callback that
did not happen to reach the node that issued the token.

**Impact:** Negligible today. The window is the seconds a user spends on a consent screen, the cost of
losing it is one click on Connect, and the deployment is single-node — the same assumption
`InMemoryPermitService` already makes (invariant 7) and `SlidingWindowRateLimiter` makes in L9. The
security property the token exists for is unaffected: an unknown token is rejected either way, so a
lost map fails closed, never open.

**Why we left it:** a Mongo collection for data whose whole life is ten minutes would need a schema, a
startup index in `MongoIndexInitializer` and a sweeper, to protect against an event (restart mid-
consent) whose remedy is already a single click. Persisting it would also mean writing a CSRF token to
durable storage, which is a slightly worse posture than keeping it in memory.

**Candidate approach:** the same Redis introduction L9 and invariant 7 already anticipate — a `SETEX`
per token is a natural fit, and single-use consumption is a `GETDEL`. Worth doing at the moment a
second replica appears, together with permits and rate-limit counters rather than separately.

---

## L13 — Publishing is at-least-once

**Area:** publishing outbox · `publishing.job.DeliveryRunner`, `storage.mongo.MongoDeliveryRepository`

**What:** A delivery is marked `SENT` in a separate write after the platform accepts it. A process that
dies between the two — or a send that outlives `app.publishing.lease-seconds` — leaves the row `PENDING`
with a lease that eventually expires, and the next claim sends it again. The stale worker's `markSent` is
lease-fenced, so it cannot corrupt the row; it just cannot un-send the first copy.

**Impact:** An occasional duplicate email, shown as two messages. Gmail assigns its own `Message-ID` on
send and discards any the message carries, so there is no header that would let a client collapse the
copies. Nothing is ever lost, which is the side worth erring on for a digest.

**Why we left it:** exactly-once needs the platform to take part — an idempotency key it honours — and
`users.messages.send` accepts none. Asking Gmail whether the message was already sent would need a read
scope on the sending account, defeating the reason it is send-only. Sending *before* recording would lose
messages instead of duplicating them.

**Candidate approach:** for a platform whose API accepts an idempotency key (Slack's `client_msg_id`,
most HTTP messaging APIs), pass the delivery id as `reference` and the window closes for that publisher
with no framework change. The scheduler is also single-node, like everything in invariant 7; the lease is
what would keep a second replica honest.
