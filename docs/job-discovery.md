# Job discovery

Finding and ranking job postings, built almost entirely out of generic features. This document is the
map; each piece is documented where it lives.

## What is actually job-specific

One connector, one prompt, and one row of config. That is the whole of it:

| Piece | Where | Generic? |
|---|---|---|
| `JOB_BOARDS` connector, its five platforms + normalisers | `ingestion/connector/ats/` — see [`connectors.md`](./connectors.md) | **No.** Irreducibly source-specific |
| `job-fit` prompt and task | `config/prompts.json` | Config, not code |
| The daily digest itself | one row in `digests` | Data, not code |
| Ageing closed postings out | retention — [`knowledge-lifecycle.md`](./knowledge-lifecycle.md) §4b | Generic |
| Filtering by company / remote / pay / date | range filters — [`opensearch-index.md`](./opensearch-index.md) | Generic |
| One result per posting | `maxChunksPerEntity` | Generic |
| Collapsing the same role from two boards | duplicate collapsing | Generic |
| Ranking postings against a CV | document-as-query | Generic |
| Scoring the shortlist and delivering it | [`digests.md`](./digests.md) | Generic |

## Setting it up

**1. Index the CV.** Add a `LOCAL_FS` knowledge pointing at the folder holding it, and note the
entity id from `GET /api/knowledge/{id}/entities`. **Wait until it is `INDEXED`**: a local file keeps
only a `fileRef`, so its text is read back from its chunks, which do not exist until indexing finishes.

**2. Add the companies.** **One** `JOB_BOARDS` knowledge, with `inputs.companies` listing company
handles and `inputs.locations` listing the cities you would work in. Which platform hosts each company
is resolved for you — check a batch of candidates first with `POST /api/connectors/job-boards/lookup`,
or the same helper in the console. `retentionPeriod` inherits the connector default of 14 days.
Backfill off; the connector is forward-only anyway.

**List the cities, not just `India`** — most boards file a role as plain `Bengaluru` with no country,
so `["India"]` alone kept 3 of Stripe's 36 Indian roles. See [`connectors.md`](./connectors.md).

**3. Create the digest.**

```jsonc
{
  "name": "New roles for me",
  "query": "roles I could do next",
  "sourceEntityId": "ent_…",              // the CV
  "knowledgeIds": ["kn_job_boards"],       // the companies knowledge
  "filters": { "metadata.remote": true },
  "window": "1d",
  "interval": "1d",
  "taskId": "job-fit",
  "topK": 10,
  "collapseDuplicates": true,
  "maxChunksPerEntity": 1,
  "onlyNew": true
}
```

That is the entire job-discovery pipeline. Every field is a generic capability.

## Coverage: what these boards actually carry

Measured against the live APIs on 2026-09-02, not estimated.

**180 company names were probed.** The answer changed twice while measuring, both times because too
few platforms had been checked — Meesho, CRED and Paytm are on **Lever**, and Swiggy and Freshworks on
**SmartRecruiters**. An earlier draft of this document called all five absent. They are not. Check
every platform before concluding a company is unreachable; that is what the lookup endpoint is for.

| Platform | Auth | Measured |
|---|---|---|
| Greenhouse / Lever / Ashby | none | 31 boards found, **~904 India roles** |
| Workday | none | 9 of 22 URL guesses resolved → **1,827 postings** |
| SmartRecruiters | none | Swiggy 71 (**70 in India**), Freshworks 157 |

Per-board, the India share varies by an order of magnitude:

| Board | Total roles | In India |
|---|---|---|
| Paytm (Lever) | 198 | 174 (88%) |
| Adobe (Workday) | 742 | 133 (18%) |
| Databricks (Greenhouse) | 857 | 92 (11%) |
| Swiggy (SmartRecruiters) | 71 | 70 (99%) |
| Sarvam (Ashby) | 62 | 62 (100%) |
| Stripe (Greenhouse) | 592 | 36 (6%) |
| Freshworks (SmartRecruiters) | 157 | 31 (20%) |
| Zeta (Lever) | 21 | 20 (95%) |
| Nium (Lever) | 41 | 15 (37%) |

Two things follow. **Realistically several thousand India roles are reachable** — global product
companies, their Bengaluru/Hyderabad/Pune/Noida GCCs, and funded Indian startups. And **the India
share of what is fetched is often a tenth**, which is why `inputs.locations` is a cost control, not a
nicety: everything kept is parsed, chunked and embedded.

Workday misses are wrong URL guesses, not absent tenants — Accenture, Walmart, IBM, Visa and Qualcomm
all use it. That is a lookup problem, and the triple has to be pasted from the careers URL.

### What is still out of reach

Anything posted only to Naukri, Instahyre, Hirist, Wellfound or LinkedIn, every company on an Indian
ATS (Darwinbox, Keka, Zoho Recruit), and bespoke careers pages — so most services companies and
smaller Indian firms. There is no fix inside this design: Naukri never published a job-search API,
LinkedIn and Indeed retired theirs, and scraping breaches their terms.

Treat the companies list as a **watchlist you keep widening**, not as market coverage. Reach is a
direct function of how many names are in it, which is the whole reason a company is an iterable and
the lookup endpoint exists.

### Why not an aggregator

Aggregators (Adzuna, Careerjet, Google for Jobs) index postings copied from many sources. They are
useful for *discovering companies you had not thought of*, but they carry duplicates, staleness and
often no direct apply link — and the big ones are closed to new users. Adzuna's India endpoint is
live and free, but it does not reach Naukri's inventory either. With five platforms in play the direct
route now has both the better data and the breadth, so Adzuna stays deferred; see `ROADMAP.md`.

## Compensation: expect it to be absent

`metadata.compMin`/`compMax` parse Indian notation — Indian digit grouping (`₹15,00,000`), `LPA`,
lakh and crore — as well as the western forms. In practice that will almost never fire on these
boards.

Of **211 India-located postings** sampled across the platforms, **0** stated pay in any
form. US postings do (**9** of 1,426 sampled parsed a real band, and the true rate is higher — the
sample truncated long descriptions and comp usually sits at the end), because pay-transparency law in
several US states requires it. India has no such requirement.

The practical consequence: **a filter on `metadata.compMin` excludes nearly every Indian role**, since
a posting with no stated pay has no value to compare. Filter on company, location, seniority or
posted-date instead, and treat comp as a bonus when it happens to be there.

> **A comp filter without a currency filter is meaningless.** `compMin`/`compMax` are plain numbers in
> the index, so `compMin >= 150000` reads as USD on one posting and INR on the next — a filter that
> looks precise and is not. `metadata.compCurrency` is recorded alongside whenever a range is parsed;
> pair the two, e.g. `{"metadata.compCurrency": "INR", "metadata.compMin": {"gte": 1500000}}`.

## Things to know before trusting the output

- **Fit scores are not comparable across runs.** An LLM's 8 today is not an 8 next week, so the digest
  ranks and takes the top N rather than thresholding. The score is persisted for calibration; do not
  build a "fit >= 8" gate on it.
- **`job-fit` uses the `lite` profile** because it runs over every new posting every day. Judgement
  quality is bounded by that choice; raise the profile if the reasons read as shallow.
- **Seniority, remoteness and pay are only as good as the posting.** The normalisers return null rather
  than guessing, so a filter on `metadata.seniority` silently excludes every posting whose title states
  no level — which is many of them, and a filter on comp excludes nearly every Indian one. Prefer
  filters on facts boards state structurally (`metadata.company`, `metadata.location`, Ashby's
  `remote` and pay bands).
- **A closed posting lingers** until its retention window elapses; it stops appearing in *digests*
  immediately, since the window is a day. See [L2b](./limitations.md).

## Not built

Aggregator APIs (Adzuna and similar), email delivery, and dismiss/applied tracking. See the
limitations doc and `digests.md`.
