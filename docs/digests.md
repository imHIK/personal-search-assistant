# Digests

A **digest** is a saved search plus a schedule, a look-back window, and an optional prompt-catalogue
task over the results. Each execution is kept as a **run**.

It is deliberately generic. The job-hunt case that motivated it — new postings scored against a CV —
is one row of the `digests` collection, not a feature: see [`job-discovery.md`](./job-discovery.md).
The same shape gives "everything new in Drive about project X, weekly" with no code involved.

---

## Shape

```jsonc
{
  "name": "New backend roles",
  "query": "roles I could do next",   // with sourceEntityId set, this is INTENT, not the search text
  "sourceEntityId": "ent_…",          // search BY a document instead of by text; optional
  "knowledgeIds": ["kn_…"],           // empty searches everything
  "filters": { "metadata.remote": true },
  "window": null,                     // how far back a run looks; null = no time bound (the default)
  "interval": "1d",                   // or "cron"; resolved by the same ScheduleResolver as ingestion
  "taskId": "job-fit",                // a prompt-catalogue task over the results; null = results only
  "topK": 10,
  "collapseDuplicates": true,
  "maxChunksPerEntity": 1,
  "onlyNew": true,                    // what makes a digest a digest
  "enabled": true,
  "channelIds": ["chn_…"]              // publishing channels each run is sent to; empty = nowhere
}
```

A digest needs **either** `query` **or** `sourceEntityId`; neither is a 400. Everything else has a
default.

> **Scope it with `knowledgeIds`.** Empty means every source, which is the search API's own default and
> is usually wrong for a digest. A one-off search is read by someone who can see what came back; a
> digest runs unattended and accumulates a "seen" set, so an unscoped one quietly starts reporting —
> and permanently marking as seen — whatever else happens to be indexed. The console's create form
> offers the source list for this reason.

## A run, in three steps

**1. Bound to the window.** The window becomes a range filter on the chunk's `indexedAt`, an existing
mapped `date` field — no schema of its own.

> Note that is *indexed since*, not *first seen*. An item re-indexed inside the window reappears here,
> and retention makes that routine: an entity that ages out is re-created by the next walk with a fresh
> `indexedAt` while being the same posting the user already saw. Step 2 is what stops that reaching
> them, which is why it cannot be skipped.

> **And it is *indexed* since, not *written* since.** For a feed that is re-walked constantly — the
> case digests were built for — the two are close enough. For a source that is ingested once and then
> left alone they are not: a week later every chunk sits outside a one-day window, and the digest is
> empty on every run from then on. Nothing about the query is wrong, and no amount of rewriting it
> helps. This is why **the window defaults to no time bound**, both in the model and in the console's
> create form, and why an empty run counts what the window cost it (below). Newness is `onlyNew`'s
> job; the window is only an extra bound on top of it.

**2. Drop what was already reported.** An item is new when it is in the window *and* absent from every
earlier run of this digest. Keyed on the **entity**, not the chunk: a chunk id changes whenever a
document is re-chunked, so a chunk-keyed check would re-report documents the user has seen.

Because that filtering happens after retrieval, a run asks for `topK × app.digest.new-item-multiplier`
candidates. Without over-fetching, a digest whose top results are all familiar reports nothing while
new items sit just below the cut.

**3. Optionally run a task.** Any task in the library — bundled or user-written, see
[`tasks.md`](./tasks.md) — via `SearchAgent.runTaskWithSources`. An empty result set skips the call
rather than spending it on a prompt with no sources.

## Annotations: joining a reply back to the results

A task whose reply describes its sources one by one has that reply read onto the items it describes.
`TaskSpec.annotatesArray` (`"annotates"` in the file, derived for a user task) names the array; each
element names its source in a `source` field, and everything else on the element becomes an entry in
that run item's `annotations` map.

The join is **positional**, and the ordering is a contract the prompt builder already keeps: sources
are numbered from 1 in the order they were rendered, so element *n* describes source *n*. That list
has to come back from the agent rather than being assumed equal to the hits — a whole-entity task
collapses several chunks of one document into one source before numbering them, which is why
`runTaskWithSources` exists at all.

`annotations` is an open map rather than named fields. The keys come from the task, and tasks are
user-written, so typing them would mean a schema change per question anyone wants asked — and would
force the console to branch on which task produced a run.

Everything here degrades to *no annotations* rather than failing. A model that ignored the requested
shape, wrapped its object in prose, or numbered a source dropped for budget costs the annotation and
nothing else: the items are real search results and worth showing, and `taskOutput` still holds the
reply verbatim for whoever wants to see why.

That fallback is what the console renders as the run's **Summary** — a boxed panel above the items,
sharing the search answer's Markdown renderer and its `[n]` citation chips, so a chip scrolls to the
result the sentence rests on. It resolves `n` the same way the annotation join does, which is why the
console reads the digest's task `sourceText`: under `ENTITY` the model numbered one source per
document, not one per hit. A **built-in** task reports no `sourceText` (`TaskDto` redacts a bundled
entry's spec), so the console falls back to the uncollapsed mapping there — right for `CHUNK`, the
default, and identical under either mode for any run whose items are already one per document. The
panel is shown only when nothing was annotated: the same text twice, once as prose and once as
badges, is noise.

## Why a quiet run is quiet

A run records `candidates` (what the search returned), `suppressed` (how many were dropped as already
reported), and `outsideWindow` (what the same search finds with the window removed, counted **only**
when the windowed search found nothing — one extra search on a path that has no results to slow down,
and a failure there costs the hint, not the run). Without them these outcomes are indistinguishable,
and all of them used to render as "no results":

| candidates | outsideWindow | items | meaning |
|---|---|---|---|
| 0 | 0 | 0 | nothing matched — the query or the scope is wrong |
| 0 | >0 | 0 | the look-back window is empty, the corpus is not — widen it |
| >0 | — | 0 | everything matched was already seen — working correctly |
| >0 | — | >0 | new items |

"Why is my digest quiet?" is unanswerable without that distinction, and the second row is the one that
sends people to rewrite a query that was never the problem.

## Failures are recorded, not thrown

A search or task failure is stored on the run as `error` and the run is saved anyway. A scheduled job
that throws leaves no trace a user will ever see; "this digest has been broken for a week" has to be
visible in the history, and the console shows a failed run rather than an empty one. A failed run
records no items, so it also cannot poison the already-seen set.

## Scheduling

`DigestScheduler` follows the ingestion schedulers' shape: a fixed tick (`app.digest.poll-interval`)
bounds resolution, and each digest carries its own `nextRunAt`. Cadence resolves through the same
`ScheduleResolver`, so a digest accepts the cron and interval forms already documented rather than
inventing a second syntax: a cron is UTC, 5-field Unix or 6/7-field Quartz, and one that parses as
neither is a `400` on create or on an edit that sends a schedule.

Two details worth knowing:

- **A new digest has a null `nextRunAt`, meaning "due now"** — it runs on the next tick rather than one
  whole interval after creation. A digest you just made and cannot see the output of looks broken.
- **The due time is rolled forward *before* the run.** A digest whose run throws would otherwise stay
  due and be retried every tick, turning one broken digest into a hot loop against the LLM.

A **disabled** digest is never scheduled but can still be run by hand — that is the difference between
pausing and deleting.

## Editing, and forgetting

`PATCH` accepts every field. A key that is **absent** is left alone; a key present as **`null` clears**
the field. That difference is the whole point: half the console's controls for these fields exist to
turn something *off* — no look-back window, no task, no per-document cap — and each of them sends a
null. Read as "unchanged", they answered 200 and changed nothing, so a digest could be given a window
and never have it taken away. `PATCH` therefore reads the request body as a tree rather than binding
it to a record, because binding cannot tell an absent key from a null one. A field carrying the wrong
JSON type is a 400 rather than a silently dropped edit.

`name` has no empty state and rejects a null; `topK`, `onlyNew`, `enabled` and `collapseDuplicates`
are primitives with no "off", so clearing one means its default rather than an error.

An edit **never touches the run history**, which matters more than it looks: the history *is* the already-seen set, so the former
delete-and-recreate route silently made an edited digest re-report its whole window. Renaming a digest
cost it its memory, and nothing in the API said so.

Clearing the seen-set is therefore a separate operation. `POST /api/digests/{id}/reset-history` sets
`historyResetAt`, and `reportedEntityIds` is bounded by it — so the runs stay readable while the next
run starts from nothing. Deleting the runs instead would clear the seen-set *and* the audit trail, and
a user widening a query wants the backlog, not amnesia about what was sent last week.

An unparseable `window` or `interval` is a 400 rather than being accepted. `Durations.parse` returns
null on a typo, which previously became a digest with no time bound, or one that never runs — created
with a 200 either way.

## API

| Endpoint | Effect |
|---|---|
| `GET /api/digests` | list |
| `POST /api/digests` | create; 400 if it names neither a query nor a source document |
| `GET /api/digests/{id}` | read one |
| `PATCH /api/digests/{id}` | edit any field; **absent** = unchanged, **`null`** = clear. So `{"enabled": false}` still pauses, and `{"window": null}` really does remove the look-back |
| `POST /api/digests/{id}/reset-history` | forget what has been reported, keeping the runs |
| `DELETE /api/digests/{id}` | delete, cascading its runs |
| `POST /api/digests/{id}/run` | run now; a failed run comes back as a run carrying `error`, not a 5xx |
| `GET /api/digests/{id}/runs` | history, newest first (`?limit=` capped at 100, `?offset=`) |
| `GET /api/digests/{id}/runs/{runId}` | one run; 404 when it belongs to another digest |
| `GET /api/digests/{id}/runs/latest` | the most recent run, 404 if it has never run |
| `GET /api/deliveries?refId={runId}` | what one run was sent to (see *Sending results to channels*) |

`GET /api/entities/{id}` is adjacent rather than part of this API, but exists for it: a digest that
searches *by* a document holds only `sourceEntityId`, and the console was rendering "Like ent_3f9…"
as the digest's description.

## Storage

`digests` and `digestRuns` in Mongo. A run stores a **projection** of each hit — entity id, chunk id,
title, uri, score, snippet, and any `annotations` — not the full chunk text, which the entity already
holds and which would grow the collection without bound. A run also carries `candidates`,
`suppressed` and `outsideWindow`; `digests` carries `historyResetAt`.

Run history is currently unbounded, though `historyResetAt` bounds the *seen-set* read. See
[L8](./limitations.md).

## Sending results to channels

`channelIds` names publishing channels ([`publishing.md`](./publishing.md)). After a run is recorded it is
queued once per channel when it is worth a message:

| run | sent? |
|---|---|
| found items | yes — the items with their annotations, and the task's reply as a summary when it annotated nothing |
| failed | yes — a short "*name* failed" notice carrying the error |
| quiet (nothing new) | no |

A quiet run sends nothing because a daily "nothing new" trains people to ignore the message that
matters. A failure sends because a digest someone relies on by email is exactly the one whose run history
nobody opens. **Run now** sends like a scheduled run — it is the quickest way to see the email.

The message mirrors the run view: the same items, annotation keys labelled the way the console labels
them (`next_step` → "Next step"), the summary under the same rule, and a link back to the digest built
from `app.console.url`.

Queuing only writes delivery rows, keyed `digestRun:<runId>:<channelId>`, so it cannot slow or fail the
run, and a replayed run queues nothing twice. A failure to queue is logged and the run is still recorded.
`GET /api/deliveries?refId=<runId>` is what the console reads for the "Sent to" line under each run.

An unknown channel id is a 400 on create and on edit, and a channel a digest still sends to cannot be
deleted (409) — dropping it from the digest instead would turn its emails off without anyone deciding to.
`PATCH` treats `channelIds` like the other fields: absent leaves it, `null` or `[]` sends nowhere.

> **Citations in the email.** The summary's `[n]` markers are rendered as written. The console resolves
> them through the task's source mode; the email does not, so under a whole-document task a run holding
> several chunks of one document can cite a number that differs from the item's position. Digests default
> to one result per document, which avoids it.
