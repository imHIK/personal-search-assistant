# Job discovery

Finding and ranking job postings, built almost entirely out of generic features. This document is the
map; each piece is documented where it lives.

## What is actually job-specific

Three connectors, one prompt, and one row of config. That is the whole of it:

| Piece | Where | Generic? |
|---|---|---|
| Greenhouse / Lever / Ashby connectors + normalisers | `ingestion/connector/ats/` — see [`connectors.md`](./connectors.md) | **No.** Irreducibly source-specific |
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

**2. Add the boards.** One knowledge per ATS, with `inputs.boards` listing the board handles, and
`retentionPeriod` set (or inherited — the connectors default to 14 days). Backfill off; these
connectors are forward-only anyway.

**3. Create the digest.**

```jsonc
{
  "name": "New roles for me",
  "query": "roles I could do next",
  "sourceEntityId": "ent_…",              // the CV
  "knowledgeIds": ["kn_greenhouse", "kn_lever", "kn_ashby"],
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

Measured against the live APIs, not estimated.

Greenhouse, Lever and Ashby are US-origin ATS platforms, and what they cover is **global product
companies with Indian offices, plus VC-backed Indian startups** — not the Indian market at large.
Sampled boards:

| Board | Total roles | In India |
|---|---|---|
| Databricks (Greenhouse) | 860 | 88 (10%) |
| Rubrik (Greenhouse) | 136 | 31 (23%) |
| GitLab (Greenhouse) | 222 | 36 (16%) |
| Stripe (Greenhouse) | 587 | 38 (6%) |
| Zeta (Lever) | 21 | 20 (95%) |
| Mindtickle (Lever) | 17 | 16 (94%) |
| Nium (Lever) | 41 | 15 (37%) |
| Sarvam (Ashby) | 62 | 62 (100%) |
| Groww (Greenhouse) | 5 | 5 (100%) |

So India coverage is real, but it is a **slice**: you reach it by naming the right boards, one company
at a time. What this pipeline structurally cannot see is the bulk of the Indian market — anything
posted only to Naukri, Instahyre, Hirist, Wellfound or LinkedIn, and every company on an Indian ATS
(Darwinbox, Keka, Zoho Recruit) or a bespoke careers page. Several well-known Indian employers checked
during this survey — Razorpay, Zomato, Swiggy, CRED, Meesho, Zerodha, Freshworks, BrowserStack — have
**no** board on any of the three.

Treat the connector list as a curated watchlist of employers you already care about, not as market
coverage. Broad coverage needs an aggregator, which is why Adzuna is the tracked phase-2 addition.

## Compensation: expect it to be absent

`metadata.compMin`/`compMax` parse Indian notation — Indian digit grouping (`₹15,00,000`), `LPA`,
lakh and crore — as well as the western forms. In practice that will almost never fire on these
boards.

Of **208 India-located postings** sampled across Greenhouse, Lever and Ashby, **0** stated pay in any
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
