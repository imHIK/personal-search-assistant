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

## L3 — No format-specific parsers (everything funnels through Tika)

**Area:** Indexing · content parsing (`indexing/parser/*` — `ContentParser`, `CdiParserRegistry`,
`TikaContentParser`, `PlainTextParser`)

**What:** Only two parsers exist: `PlainTextParser` and a single general-purpose `TikaContentParser`
that handles PDF/DOCX/HTML/spreadsheets/… all through Apache Tika's generic extraction path. There is
no dedicated parser per file type. The registry is already built for this — `ContentParser` beans are
discovered via CDI and selected by MIME type, with `priority()` letting a specific parser win over the
general fallback — so the extension points exist; they're just not populated yet.

**Impact:** Low–Medium. The pipeline is correct, but extraction *quality* is whatever Tika's generic
path yields. Format structure (tables, headings, slide/page boundaries, spreadsheet cells) can be
flattened or dropped, which degrades chunking and search relevance for those formats. No data loss.

**Why we left it:** Tika covers the long tail of formats cheaply with one implementation. Per-format
parsers are worth adding only where quality demands it, so we deferred pending the review.

**Candidate approaches (for later):** add format-specific `ContentParser` beans (e.g. dedicated
PDF / DOCX / spreadsheet parsers) with a lower `priority()` than Tika for their MIME types. The CDI
registry auto-discovers them and prefers them over the fallback — no wiring changes required.

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
