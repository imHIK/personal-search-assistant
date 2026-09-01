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
  "window": "1d",                     // how far back a run looks; null = no time bound
  "interval": "1d",                   // or "cron"; resolved by the same ScheduleResolver as ingestion
  "taskId": "job-fit",                // a prompt-catalogue task over the results; null = results only
  "topK": 10,
  "collapseDuplicates": true,
  "maxChunksPerEntity": 1,
  "onlyNew": true,                    // what makes a digest a digest
  "enabled": true
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

**2. Drop what was already reported.** An item is new when it is in the window *and* absent from every
earlier run of this digest. Keyed on the **entity**, not the chunk: a chunk id changes whenever a
document is re-chunked, so a chunk-keyed check would re-report documents the user has seen.

Because that filtering happens after retrieval, a run asks for `topK × app.digest.new-item-multiplier`
candidates. Without over-fetching, a digest whose top results are all familiar reports nothing while
new items sit just below the cut.

**3. Optionally run a task.** Any task in `config/prompts.json`, via `SearchAgent.runTask`. An empty
result set skips the call rather than spending it on a prompt with no sources.

## Failures are recorded, not thrown

A search or task failure is stored on the run as `error` and the run is saved anyway. A scheduled job
that throws leaves no trace a user will ever see; "this digest has been broken for a week" has to be
visible in the history, and the console shows a failed run rather than an empty one. A failed run
records no items, so it also cannot poison the already-seen set.

## Scheduling

`DigestScheduler` follows the ingestion schedulers' shape: a fixed tick (`app.digest.poll-interval`)
bounds resolution, and each digest carries its own `nextRunAt`. Cadence resolves through the same
`ScheduleResolver`, so a digest accepts the cron and interval forms already documented rather than
inventing a second syntax.

Two details worth knowing:

- **A new digest has a null `nextRunAt`, meaning "due now"** — it runs on the next tick rather than one
  whole interval after creation. A digest you just made and cannot see the output of looks broken.
- **The due time is rolled forward *before* the run.** A digest whose run throws would otherwise stay
  due and be retried every tick, turning one broken digest into a hot loop against the LLM.

A **disabled** digest is never scheduled but can still be run by hand — that is the difference between
pausing and deleting.

## API

| Endpoint | Effect |
|---|---|
| `GET /api/digests` | list |
| `POST /api/digests` | create; 400 if it names neither a query nor a source document |
| `GET /api/digests/{id}` | read one |
| `PATCH /api/digests/{id}` | `{"enabled": false}` — pause or resume |
| `DELETE /api/digests/{id}` | delete, cascading its runs |
| `POST /api/digests/{id}/run` | run now; a failed run comes back as a run carrying `error`, not a 5xx |
| `GET /api/digests/{id}/runs` | history, newest first (`?limit=`, capped at 100) |
| `GET /api/digests/{id}/runs/latest` | the most recent run, 404 if it has never run |

Fuller editing is delete-and-recreate: `PATCH` covers only `enabled`, which is the operation that
actually recurs.

## Storage

`digests` and `digestRuns` in Mongo. A run stores a **projection** of each hit — entity id, chunk id,
title, uri, score, snippet — not the full chunk text, which the entity already holds and which would
grow the collection without bound.

Run history is currently unbounded. See [L8](./limitations.md).

## Later

Email is a thin consumer of `DigestRun`: `quarkus-mailer` plus SMTP config and a sender that formats
the latest run. Nothing in the model needs to change for it.
