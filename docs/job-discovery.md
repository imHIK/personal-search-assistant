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
| Filtering by company / remote / date | range filters — [`opensearch-index.md`](./opensearch-index.md) | Generic |
| One result per posting | results are one per entity on every search — [`opensearch-index.md`](./opensearch-index.md) | Generic |
| Collapsing the same role from two boards | duplicate collapsing | Generic |
| Scoring the shortlist and delivering it | [`digests.md`](./digests.md) | Generic |
| Extracting yoe / skills / … from each posting | a METADATA task — [`tasks.md`](./tasks.md#metadata-tasks-enrichment-at-indexing-time) | Data, not code |
| Browsing every posting, filtering, marking applied / hidden | `/api/entities` + the `/jobs` page — below | Generic API; the page is job-specific config |

## Setting it up

**1. Add the companies.** **One** `JOB_BOARDS` knowledge, with `inputs.companies` listing company
handles and `inputs.locations` listing the cities you would work in. Which platform hosts each company
is resolved for you — check a batch of candidates first with `POST /api/connectors/job-boards/lookup`,
or the same helper in the console. `retentionPeriod` inherits the connector default of 14 days.
Backfill off; the connector is forward-only anyway.

**List the cities, not just `India`** — most boards file a role as plain `Bengaluru` with no country,
so `["India"]` alone kept 3 of Stripe's 36 Indian roles. See [`connectors.md`](./connectors.md).

**2. Create the digest.**

```jsonc
{
  "name": "New roles for me",
  "query": "backend engineer java",
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
all use it. That is a lookup problem, and the triple has to be pasted from the careers URL. The same
turned out to be true of a second hosted ATS: **Oracle Recruiting Cloud** carries BNY Mellon (1,386
requisitions), JPMorgan Chase (7,324) and Kotak Mahindra (9,647), and is now supported as `oraclehcm`
— addressed by a `host/siteNumber` pair read off the careers page, exactly like Workday's triple. A worked
example of that lookup across a real 126-company watchlist — what reached a board, what did not, and
the manual step each remaining one needs — is in [`job-board-companies.md`](./job-board-companies.md).

### What is still out of reach

Anything posted only to Naukri, Instahyre, Hirist, Wellfound or LinkedIn, every company on an Indian
ATS (Darwinbox, Keka, Zoho Recruit), and bespoke careers pages — so most services companies and
smaller Indian firms. Also, for now, the other hosted ATSs a real watchlist turns up: Avature (Delta),
iCIMS (Docusign), SuccessFactors (HCL), Radancy (Intuit) and RippleHire (7-Eleven). Each is one more
`BoardPlatform` bean; none is reachable today. There is no fix inside this design: Naukri never published a job-search API,
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

## Compensation is not extracted

Of **211 India-located postings** sampled across the platforms, **0** stated pay in any form, so pay
is not parsed and there is no pay filter. It is still in the posting text the `job-fit` task reads.

## Things to know before trusting the output

- **Fit scores are not comparable across runs.** An LLM's 8 today is not an 8 next week, so the digest
  ranks and takes the top N rather than thresholding. The score is persisted for calibration; do not
  build a "fit >= 8" gate on it.
- **`job-fit` uses the `lite` profile** because it runs over every new posting every day. Judgement
  quality is bounded by that choice; raise the profile if the reasons read as shallow. Its
  `contextChars` is 80000 so all ten postings of a run fit whole (median posting ~6k chars); a budget
  that does not fit them drops the tail silently, since `AnswerPromptBuilder` stops at the budget. That
  one request is ~20k tokens, which is why the `lite` connection should be Gemini rather than Groq — see
  [providers.md](./providers.md#llm-connections).
- **Seniority and remoteness are only as good as the posting.** The normalisers return null rather
  than guessing, so a filter on `metadata.seniority` silently excludes every posting whose title states
  no level — which is many of them. Prefer filters on facts boards state structurally
  (`metadata.company`, `metadata.location`, Ashby's `remote`).
- **A closed posting lingers** until its retention window elapses; it stops appearing in *digests*
  immediately, since the window is a day. See [L2b](./limitations.md).

## The dashboard (`/jobs`)

A standalone console page — no nav entry, no console chrome — that works as one combined careers page
across every `JOB_BOARDS` source, so the boards need not be checked one by one. Open
`http://localhost:8080/jobs` (or `:5173/jobs` under `frontendDev`); `SpaRoutingConfigurator` serves the
deep link.

- **It lists stored entities, not search hits.** `POST /api/entities/query` reads Mongo directly:
  every `JOB_POSTING` that is not `DELETED`, newest **first seen** (`createdAt`) first, 25 a page.
  There is no query text to rank by and no top-K cut-off, which is why this is not built on
  `/api/search`.
- **The company leads each row**, with its logo. `frontend/src/config/companies.ts` gives each known
  company a `domain`, and the browser loads its icon from DuckDuckGo's icon service — so DuckDuckGo
  sees which domains are looked up. A company with no known domain, or an icon that fails to load,
  gets a coloured monogram instead. `companyFor` also maps the spellings a board stores
  (`mastercard`, a bare Oracle tenant `CX_1001`) to the known label, and the company filter merges
  them into one chip.
- **Filters** are declared in `frontend/src/config/jobDashboard.ts`: title, company / team / platform /
  source (options from `GET /api/entities/facets`, with counts), location (substring), remote,
  seniority, posted within, first seen within, status, and show hidden. The state lives in the URL,
  so a filtered view can be bookmarked.
- **Enriched fields become filters by themselves.** The page reads the sources' `enrichTaskId`, and
  each field of that task is a filter — `NUMBER` a min/max, `BOOLEAN` yes/no, `TEXT`/`LIST` chips from
  the field's `values` or from facets. Adding a field to the task adds the filter; re-index the source
  to fill it on existing postings. They also show on each row, labelled by field name.
- **Status and hidden are user marks in `custom`.** Each posting has at most one status —
  `REACHED_OUT`, `APPLIED` or `APPLIED_COLD` — in `custom.status`, with `custom.statusAt` stamped when
  it is set; *Hide* writes `custom.hidden: true`. Both go through `PATCH /api/entities/{id}/custom` (a
  `null` removes a key). The status filter's *No status* option is a `$nin` over the other statuses,
  which also matches a posting that has none. `custom` is a third owner on the entity — the user — so neither ingestion nor
  the indexer touches it, and it survives re-ingest and re-index. Hidden postings are filtered out
  (`custom.hidden {ne: true}`) unless *Show hidden* is on. A posting removed by retention takes its
  marks with it.

The endpoints are generic — any entity type, any `metadata.*` / `enriched.*` / `custom.*` path:

| Endpoint | Effect |
|---|---|
| `POST /api/entities/query` | `{entityTypes, knowledgeIds, q, filters, sort, limit, offset}` → `{items, total, limit, offset}`. A filter value that is a scalar is equality, an array any-of, an object operators (`eq ne in nin gte gt lte lt contains exists`); an ISO date compares as a date. Paths outside `metadata.* enriched.* custom.* createdAt updatedAt` are a 400. Items carry `metadata`, `enriched`, `custom` and `enrichmentError`, never `raw` or content |
| `GET /api/entities/facets?entityTypes=&knowledgeIds=&fields=a,b&limit=` | distinct values with counts per path, list elements counted one by one; counted over the type and sources only, so picking a value never hides the others |
| `PATCH /api/entities/{id}/custom` | merges keys into `custom`; values are text, numbers or booleans, `null` removes |

## Not built

Aggregator APIs (Adzuna and similar) and email delivery. See the limitations doc and `digests.md`.
