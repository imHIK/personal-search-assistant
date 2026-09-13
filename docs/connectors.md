# Connectors

A connector is one implementation of the `SourceConnector` port — the primary extension point for
integrations. Adding a source means adding one CDI bean; nothing else in the domain, storage, or
retrieval layers changes (`CdiConnectorRegistry` discovers it by `type()`). See `ARCHITECTURE.md`
for the ports & adapters picture and `docs/indexing-design.md` for cursor/anchor semantics.

A connector is also the *grabber*: it owns its own pagination state (`CursorPosition`, an opaque
field bag the core persists and replays) and pages a source in a `CursorDirection` — **backward**
(backfill, items older than the knowledge `anchor`) and **forward** (incremental, items at/after the
anchor). The ingestion runner drives every connector identically; the only asymmetry is that a
drained backward cursor goes `EXHAUSTED` while a caught-up forward cursor goes `IDLE` and is later
re-armed by the scheduler *without its position being reset* — so forward pagination must be able to
resume from where it stopped.

| Source | `SourceType` | Adapter | Connection | Iterables |
|---|---|---|---|---|
| Local filesystem | `LOCAL_FS` | `localfs.LocalFsConnector` | none | root + one per sub-directory |
| Gmail | `GMAIL` | `google.gmail.GmailConnector` | required (OAuth) | all-mail, or one per configured label |
| Google Drive | `GOOGLE_DRIVE` | `google.drive.GoogleDriveConnector` | required (OAuth) | one per folder (tree walked at discovery) |
| Company job boards | `JOB_BOARDS` | `ats.JobBoardsConnector` | none (public boards) | **one per company**, across Greenhouse / Lever / Ashby / SmartRecruiters / Workday / Oracle HCM |

## Content: `grab()` maps, `materialize()` fetches

`grab()` returns `RawItem`s, but an item's *content* is fetched separately, by
`SourceConnector.materialize(knowledge, item)`. The ingestion runner calls it only for items it has
decided to persist — new, changed, or previously `FAILED` — **after** its checksum comparison.

The split exists because change detection cannot live in the connector. `grab` is stateless and
idempotent by contract (invariant 4) and a connector has no access to what is stored, so only the
runner can say "this one is unchanged". A connector that can derive its checksum from a cheap listing
therefore emits items carrying just that checksum plus a *reference* — for `GOOGLE_DRIVE`, the scratch
path the bytes will occupy — and transfers the bytes in `materialize()`.

The default implementation carries whatever the item already holds (`fileRef` if set, else `text`),
which is right for every connector whose content is cheap by construction: a path into the source
itself (`LOCAL_FS`), a body that had to be fetched to know the checksum at all (`GMAIL`), or a payload
that arrived with the listing (`JOB_BOARDS`). Only Drive overrides it today.

`materialize()` runs on the ingestion worker inside the cursor's lease and may throw: the page is
replayed by the cursor's ordinary retry/backoff, which is safe precisely because `grab` is idempotent
and all pagination state lives on the cursor.

## Re-index: `defaultReindexMode()` declares whether content is durable

A re-index re-reads the entity's stored content long after the walk that wrote it, so a connector has
to say whether that content can still be trusted. `defaultReindexMode()` answers exactly that:

| | `REINDEX_ONLY` (default) | `FETCH_AND_REINDEX` |
|---|---|---|
| Who | `LOCAL_FS`, `GMAIL`, `JOB_BOARDS` | `GOOGLE_DRIVE` |
| Because | inline text lives in Mongo; a `LOCAL_FS` `fileRef` names the user's own file | the `fileRef` names a *copy* staged under `download-dir`, which defaults into the temp dir the OS purges |

This is a property of where the bytes went, not of the request, which is why the API has no "refetch"
verb — a caller asks for a re-index and this decides the cost. `app.indexing.refetch-on-reindex`
(`auto` / `always` / `never`) is the operator's override.

`fetchOne(knowledge, entity)` is the fetch half: re-list one already-known item by its `externalId`.
Its contract is that the returned `RawItem` is shaped exactly as `grab` would have shaped it — same
checksum rule above all, since a checksum that differed here would make every subsequent poll see a
change that never happened. Implemented by reusing the connector's own listing-row mapping:
`files.get` for Drive, a re-stat for `LOCAL_FS`, `messages.get` for Gmail. `Optional.empty()` means
gone at the source (trashed, deleted, 404) and the caller tombstones the entity — which, since no
connector emits tombstones during a walk, is currently the only way a removal is ever noticed. The
default implementation throws; a `REINDEX_ONLY` connector is never asked.

The knowledge-wide counterpart does not call this at all: it flags `Entity.needsRefetch` and rewinds
the cursors, so the ordinary walk re-materializes the items inside the lease, permit and rate-limit
machinery it already has. See `docs/limitations.md` L11.

## Connections (credentials, separated from knowledges)

> **Connections are not only for knowledges.** A connection's `type` is a *connection type* — a
> `SourceType` name for a connector's account, or a type something else registers, such as the email
> channel's send-only `GMAIL_SEND` ([`publishing.md`](./publishing.md)). `ConnectionKindRegistry`
> resolves the type to its check: a connector that `requiresConnection()` is registered automatically
> and verified by its own `verifyConnection`; anything else is a `ConnectionKind` bean. Nothing below
> changes for connectors.

Credentials do **not** live on a knowledge. They live in a reusable, first-class **`Connection`**
(the `connections` collection) that a knowledge authenticates *through*. This is a generic framework,
not a Google-specific one: a connection is keyed by `SourceType` and carries two opaque, connector-
defined blobs — `auth` (tokens/keys) and `config` (connector-level settings) — that the core never
inspects. Separating "who am I connecting as" from "what do I want to index" is what lets one user
keep several accounts of the same connector (a personal and a work Gmail) and point different
knowledges at whichever they want, without duplicating credentials per knowledge.

- **Multiple per type, one default.** A user can register many connections of a type; exactly one is
  the default. A knowledge names a connection via `connectorDetails.connectionId`, or leaves it null
  to resolve the type's default (`ConnectionResolver`). The first connection created for a type
  becomes its default automatically.
- **Opt-in per connector.** `SourceConnector.requiresConnection()` is `false` by default, so a no-auth
  source like `LOCAL_FS` never needs one; credentialed connectors override it to `true`.
- **Verified once, at connect time.** `SourceConnector.verifyConnection(Connection)` validates the
  credentials when the connection is created/edited (Gmail hits `getProfile`, Drive hits `about`) —
  not on every knowledge or every grab. A connector is free to raise its own transport type
  (`GoogleApiException`, `AtsApiException`, `RateLimitedException`); `DefaultConnectionService` funnels
  all of them into `IllegalArgumentException`, so a rejected create/edit is a **400 whose JSON body
  carries the reason** rather than an opaque 500.
- **Errors carry their reason.** Resources build failure responses through `api.resource.ApiErrors`,
  which attaches a `{"message": ...}` JSON body. Throwing `new BadRequestException("...")` directly
  does *not*: RESTEasy Reactive keeps that text on the exception and sends `400` with
  `content-length: 0`, which is what left the console with nothing to display.
- **Lifecycle & integrity.** `ConnectionService` assigns/re-points the per-type default, blocks
  deleting a connection still bound by a knowledge, and promotes a survivor when the default is
  removed. REST surface: `POST/GET/PATCH/DELETE /api/connections`, plus `POST /api/connections/{id}/default`.

### Rate limits (the one field the core reads)

`auth` and `config` are opaque, but `Connection.rateLimit` is not: the outbound HTTP layer reads it on
every call. That is why it is a first-class component rather than a key inside a blob.

```jsonc
"rateLimit": { "rules": [ { "permits": 10, "windowSeconds": 1 },
                          { "permits": 500, "windowSeconds": 60 } ] }
```

A call must satisfy **every** rule, and is recorded in each only if all of them have room — so a
call cannot deplete the per-second window while stalled on the daily one. Each rule is a *rolling*
window holding `permits` admissions, which is why there is no separate burst setting: a key idle for a
window admits `permits` back-to-back, then nothing until those calls age out. Resolution is two
tiers: the account's own rules win, otherwise `app.ratelimit.connector.<SOURCE_TYPE>.rules`, otherwise
unlimited — which is what every connection has until somebody sets one.

Two consequences worth knowing:

- **Absent ≠ empty on PATCH.** A null `rateLimit` means "leave it unchanged", like every other PATCH
  field. Removing a limit takes an explicit `{"rateLimit": {"rules": []}}`.
- **Being throttled defers work, it does not fail it.** Background calls wait up to
  `app.ratelimit.max-wait-seconds`; past that the runner records the reopening instant and picks the
  work up later, counted against `app.ratelimit.max-deferrals` rather than the ordinary retry limit.
  Interactive calls (a search's query embedding, an answer) fail fast instead — see `docs/providers.md`.
- **A throttled ingestion cursor is visible.** It rests `RATE_LIMITED` with that instant as
  `retry.nextAttemptAt` and drops out of the claim batch until it passes, so the console can say
  "waiting on a rate limit" instead of showing a healthy-looking queued stream. Only
  `POST /api/index/knowledge/{id}/retry-failed` shortens a hold — worth doing after raising the
  account's rules, since the instant was computed against the old ones.
- **A server's `Retry-After` is clamped** to `app.ratelimit.max-penalty-seconds` (6 h) before it
  becomes a pause. The header is remote input and the instant it produces is load-bearing; if the
  server really meant longer, the next call earns another `429` and another pause.

Boards have no `Connection`, so their bucket is the platform and their rules come from
`app.ratelimit.job-boards.rules`.

> **Setting a board limit also slows the company lookup.** `POST /api/connectors/job-boards/lookup` is
> a synchronous, user-facing call, and it shares the `board:<platform>` bucket with ingestion. Because
> board calls run in `WAIT` mode, a lookup made with no tokens left waits rather than failing —
> measured at ~28 s per call against a deliberately harsh `2/1m`, up to `app.ratelimit.max-wait-seconds`.
> That is the right trade for a sync and the wrong one for somebody typing a company name.
>
> The mode cannot currently be split per path here the way it is for embeddings: `countPostings`
> (lookup) and `fetch` (ingest) come out at the same `*Api` method, so the adapter cannot tell which
> caller it is serving. Ships blank/unlimited, so nothing waits until an operator sets a limit — but if
> you set one, size it against the lookup's latency, not just the sync's.

### Google auth

Both Google connectors resolve a bearer token through the shared `GoogleAccessTokens` port
(`DefaultGoogleAccessTokens`), reading the connection's blobs:

| blob | key | meaning |
|---|---|---|
| `auth` | `accessToken` | short-lived bearer token (optional if a refresh token is present) |
| `auth` | `expiresAtEpochSec` | expiry of `accessToken`; treated as expired when absent |
| `auth` | `refreshToken` | long-lived token used to mint new access tokens |
| `config` | `clientId` / `clientSecret` | OAuth client used to refresh (falls back to app config) |

When the access token is missing or near expiry and a refresh token is present, a fresh token is
minted from the OAuth endpoint, cached in-process (keyed by connection id), **and written back onto
the connection** so it survives restarts and is shared by every knowledge on that account. The
app-level fallback client is `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` (see
`app.oauth.google.*`). Swapping in a real secret store is a drop-in replacement of the
`GoogleAccessTokens` / `ConnectionRepository` beans — no connector changes.

`DefaultGoogleAccessTokens` is only a facade: it pairs the bearer with Google's rate-limit bucket, and
delegates the credential handling to the provider-neutral `OAuthTokenService`. The refresh, caching,
write-back and revoked-grant behaviour are shared with every OAuth provider — **see `docs/oauth.md`**,
which is also where the consent flow (`POST /api/connections/oauth/google/start`) and the rule that
matters operationally live:

> A refresh token issued by a client whose consent screen is still in **Testing** is revoked by Google
> after **7 days**, no matter what this application does. The consent screen must be published
> ("In production") for a Google connection to survive. Unverified is fine for a personal deployment.

Required OAuth scopes: `https://www.googleapis.com/auth/gmail.readonly` and
`https://www.googleapis.com/auth/drive.readonly`. They are declared in `GoogleOAuthProvider`, which is
what the consent flow requests.

### Connection health

Credentials are verified at create and on an auth edit — and, until now, never again. A token that
expired afterwards left its connection reading `ACTIVE` while every sync failed: the failures scrolled
past in the log, the console showed nothing wrong, and the first real signal was that the data had
quietly stopped updating.

`ConnectionHealthScheduler` re-runs each connection's check (`verifyConnection` for a connector's
account, `ConnectionKind.verify` otherwise) on every connection every
`app.connections.health-interval` (30m) and records the outcome as `ConnectionStatus` + `lastError`.
It is no longer the only signal: a provider that reports the grant is permanently dead
(`CredentialsRejectedException`) marks the connection `ERROR` on the spot, so the skip and the
console's reconnect banner happen on the next tick rather than up to 30 minutes later — see
`docs/oauth.md` §4.
`POST /api/connections/{id}/test` does the same on demand, and is what the console's **Test** button
calls. Both return 200 whether or not the check passed — a bad credential is a result to display, not
a 4xx.

`IngestionJob` then **skips** knowledges whose connection is in `ERROR`, instead of burning a lease and
a permit per tick to fail. Three properties of that are deliberate:

- **Nothing is paused and no knowledge state is written.** The skip is derived from the connection's
  status, so a connection that starts working again resumes sync on its own — and a user's own pause is
  never overridden by the framework.
- **`DISABLED` is left alone.** That is an operator decision; a passing credential check must not
  silently re-enable it.
- **Any doubt runs the knowledge as before.** A missing connection, an unknown type or a lookup failure
  all fall through to running it. This is an optimisation, and it must never be the reason a sync stops.

It detects a dead credential; it cannot fix one. Automatic re-consent is tracked in `ROADMAP.md`.

## Gmail

**Inputs** (`knowledge.inputs`): optional `labelIds` (a list — one iterable per label; omit for a
single all-mail stream) and optional `query` (a Gmail search expression AND-ed into every window).

Pagination is direction-specific because `messages.list` only ever returns newest-first and the
sole window control is the `after:`/`before:` predicate:

- **Backward** — `before:<anchor>`, follow `nextPageToken` until it runs out. Position: `{pageToken}`.
- **Forward** — `after:<floor>` where `floor` starts at the anchor and advances to the newest
  `internalDate` seen once a run drains, so the next scheduled arm only lists newer mail. Position:
  `{floorMs, pageToken?, maxMs?}`. The floor is applied at second granularity, so the boundary second
  can re-list a few already-seen messages; the ingestion runner's checksum change-detection drops
  them, giving progress without gaps.

Each message maps to a `RawItem` of type `EMAIL` with headers (subject/from/to/date) and the decoded
plain-text (or de-tagged HTML) body carried inline in `text`. Checksum is `gmail:<id>;hist:<historyId>`.

## Google Drive

**Inputs** (`knowledge.inputs`): optional `folderIds` (list of root folders to index; defaults to
`root` = My Drive). Discovery walks the folder tree breadth-first and emits one non-recursive iterable
per folder — a clean fit for Drive's per-parent (`'<id>' in parents`) API.

Drive exposes `modifiedTime` ordering and predicate, so each direction is O(page):

- **Backward** — `modifiedTime < anchor`, `orderBy=modifiedTime desc`, page by token to `EXHAUSTED`.
- **Forward** — `modifiedTime >= floor` ascending, high-water floor advancing to the newest
  `modifiedTime` seen (same resume-safe scheme as Gmail). `version` is the checksum, so re-listed
  boundary files are change-detected away.

Content mapping splits by type: Google-native docs are exported to text
(Document→text/plain, Spreadsheet→text/csv, Presentation→text/plain) and carried inline as a `PAGE`;
binary files are downloaded to a local scratch dir (`app.ingestion.google-drive.download-dir`) and
referenced by `fileRef` so the existing Tika path parses them exactly like a local `FILE`. Files over
`max-file-bytes` and unsupported native types (forms, maps, drawings) are skipped.

Neither transfer happens during the walk — both are deferred to `materialize()` (below). The walk maps
a file from listing metadata alone, including the scratch path the bytes *will* occupy, so an item the
runner skips as unchanged costs a listing row and nothing else. That matters because the forward
boundary is re-listed on every arm by design: fetching in `grab()` meant re-downloading those files
every arm only for change detection to throw the bytes away, and re-fetching the whole corpus on any
backfill or membership re-walk.

## Company job boards (`JOB_BOARDS`)

**Inputs**: `companies` — the companies to watch, plus optional `locations` (below). One knowledge, one
list; you widen the net by adding names.

**A company is an iterable, not a platform.** Which ATS a company uses is an implementation detail of
fetching, and requiring someone to know it before they can watch a company makes widening the net
needlessly expensive — Meesho and CRED are on Lever, Databricks on Greenhouse, Tekion on Ashby. So
there is one `SourceType` and one connector, with a `BoardPlatform` bean per platform behind it.

`discover()` resolves each name by probing every platform in turn and records the answer in the
iterable's `attributes` (`platform`, `handle`). The framework snapshots those onto the `Cursor`, so
`grab()` reads the platform straight back and never re-probes — which is what the attributes mechanism
exists for. An entry may pin a platform explicitly as `platform:handle` (`lever:paytm`), which matters
for a company mid-migration with boards on two platforms, where probe order would otherwise decide
silently.

An unresolvable company is skipped by `discover` and logged. `verify` throws only when **every** name
fails: all of them missing is a typo or an outage, whereas some of them missing is normal — plenty of
companies are on none of these platforms.

### Looking a company up before adding it

`POST /api/connectors/job-boards/lookup` takes candidate names and reports, per name, the platform that
hosts it, the handle and how many postings that board holds. The console renders it above the companies
field, driven by a `companyResolver` flag on the connector descriptor rather than by any component
branching on `SourceType`.

It exists because **reach here is a function of how many companies are named**, so the list is edited
constantly and a name that resolves to nothing is invisible otherwise — it would be a line in the log of
a knowledge that was already created. `found: false` is a normal answer, not an error.

The lookup answers "does this name work?", but it cannot answer "what should I type?" — so the console
also ships a catalog of verified handles (`frontend/src/config/companies.ts`) rendered as a checkbox
list by the descriptor's `picklist` field kind, with a free-text row beside it for anything not in the
catalog. Entries are stored as **bare handles, not pinned `platform:handle`**, so a company that
migrates ATS keeps resolving; the platform shown next to each name is display only. Every catalog entry
must be verified through this endpoint before it is added — an unverified handle there looks
authoritative and is worse than no catalog.

Which of a real 126-company watchlist reached a board, which did not, and the manual step each
remaining one needs is worked out in [`job-board-companies.md`](./job-board-companies.md).

`locations` uses the same control against `frontend/src/config/locations.ts`. Because
`matchesLocation` is a lowercased **substring** test, a city with two accepted spellings needs both
terms — "bengaluru" does not contain "bangalore" — so those rows store a group of values behind one
tick rather than a single term.

The counts are the whole board, before `locations`. That is deliberately the pre-filter number: it is
what the connector will actually fetch, so it says what a company costs, and the Indian share is roughly
a tenth of it.

Probing is live and sequential across platforms, so it is slow — three names took ~30s, most of it spent
on the one that resolved nowhere and therefore paid for every probe. Hence the 50-name cap: an unbounded
list would be a long-running request against APIs that are someone else's to pay for.

### Adding a platform

Add an `@ApplicationScoped` bean implementing `BoardPlatform` (`id()`, `countPostings()`, `fetch()`),
discovered by CDI and registered nowhere — the same shape as connectors, parsers and chunking
strategies. `countPostings` must return an empty `OptionalInt` rather than throw for a miss, since
resolution probes every platform and a miss is the normal outcome for all but one; `hasBoard` is a
default method over it.

It returns a **count**, not a boolean, because every platform's existence check already knew one —
Greenhouse, Lever and Ashby from the listing size, SmartRecruiters from `totalFound`, Oracle HCM from
`TotalJobsCount`, Workday from
`total`. Throwing it away and re-fetching the board to answer "how big is it?" would double the
requests for a number already in hand, which is what makes the lookup endpoint below cheap.

### Snapshot-shaped

Every supported platform returns the entire current board in one request — no `updated_after`, no
continuation token, no meaningful pagination — so `JobBoardsConnector` implements `SourceConnector`
directly, as `LocalFsConnector` does. The seed `TimeWindow` is ignored and every grab returns one page
with `hasMore=false`. The expensive half of the machinery still works: change detection skips any
posting whose checksum is unchanged, unless it is `FAILED` or `DELETED`.

Backward cursors are not supported — a job board has no history worth walking. A closed posting simply
stops appearing; boards send no tombstone, which is why this connector opts into a 14-day retention
window against a 3-hour poll.

Per-platform quirks:

- **Greenhouse** — `?content=true` makes one call sufficient. **`updated_at` is *not* the change
  signal**, despite being the obvious candidate: it moves in bulk. Measured live, 178 of GitLab's 227
  postings share one `updated_at` to the second and 233 of Okta's 313 do, so trusting it re-embedded
  three quarters of a board for a change that never touched the text. The checksum is
  `AtsNormalization.changeStamp(title, location, content)` instead.
- **Lever** — publishes **no update timestamp**, only `createdAt`. A checksum from `createdAt` alone
  would never move, so an edited posting would be skipped forever (an invariant-3 violation). The
  checksum hashes the body too.
- **Ashby** — the richest: states `isRemote` structurally, publishes real pay bands with
  `includeCompensation`, and is the only one that may carry a close date, which becomes
  `Entity.expiresAt` and beats the knowledge-level retention window. It publishes **`publishedAt` and
  no `updatedAt`**; reading the absent field made the checksum a constant, so an edited posting was
  never re-indexed — an invariant-3 violation that survived because the test fixture invented the
  field. It uses `changeStamp` too.
- **Workday, Oracle HCM, SmartRecruiters, Lever** — all hash the body into the checksum because none
  publishes a timestamp that moves on an edit. Note they hash the **body only**, where Greenhouse and
  Ashby now stamp title and location too; a role relocated without a description change is therefore
  still missed on those four. Aligning them means one forced re-index of every posting they hold, so
  it is deliberately not bundled here.
- **SmartRecruiters** — the only platform that pays **per posting**, and the only one that reaches
  Swiggy (71 postings, 70 in India) and Freshworks. Two consequences below.

#### SmartRecruiters costs 1 + N requests

Its listing carries metadata only — no description — so every posting needs a second call. A board of
N postings therefore costs `1 + N` requests where Greenhouse costs 1, and change detection cannot help:
the runner only skips a posting *after* the connector has produced a `RawItem`, which requires the text.

This matters twice over now that calls are rate limited: those `1 + N` requests all charge the same
`board:smartrecruiters` bucket, so a tight `app.ratelimit.job-boards.rules` slows a per-posting
platform far more than a snapshot one.

Two things keep it bounded:

- **The location hint is applied to the listing first**, so only survivors are fetched in full.
  Measured live: Freshworks is 157 postings, and filtering on `india` first made **47 detail calls
  instead of 157**. Take the hint seriously when adding a per-posting platform — this is the difference
  between a viable connector and one that hammers a public API eight times a day.
- **A failed detail fetch skips that posting**, not the board. A posting withdrawn between the listing
  and the fetch must not cost the other 156.

Expect it to be slow: 47 sequential detail calls took ~14s. That is fine behind a lease and a 3-hour
poll, but it is an order of magnitude slower than the single-call platforms.

Two API quirks worth knowing:

- **An unknown company returns 200 with `totalFound: 0`**, not a 404 — so `hasBoard` reads the field.
  Treating the status code as the answer would resolve every company ever typed to this platform.
- **No update timestamp is published.** `releasedDate` is when the posting first went live and does not
  move on an edit, so — as with Lever — the checksum hashes the body, or an edited posting would be
  skipped forever (invariant 3).

#### Workday is addressed by a triple, and queries rather than filters

A Workday site is a `tenant/site/wd` triple (`adobe/external_experienced/wd5`) or the career-site URL,
and **none of the three parts is guessable** — 13 of 22 blind attempts failed on companies that
certainly use Workday. So `WorkdaySite.parse` returning empty is what tells the connector a bare
company name is not a Workday site, and `hasBoard` answers `false` for one **without any network
call**. Resolution asks every platform about every name; a speculative POST per name would slow adding
companies for no possible benefit.

Search is a `POST` with the paging window in the body — the reason `AtsHttp` has `postJson` — and pages
at 20 against sites holding several hundred (Adobe: 742).

**The location hint is sent as a query, never applied as a filter** — and the distinction is the whole
design. Workday's search *result* is systematically less complete than its detail: a bare city with no
country (`"Bengaluru"`), and for a multi-site role a *count* with no place at all (`"5 Locations"`).
Filtering on that would drop a Bengaluru role whenever the filter names "India", and drop every
multi-site role outright — the same silent miss already measured on Stripe. But `searchText` runs
against Workday's own index, which reads the whole record, so *asking* finds the multi-site role that
*filtering* would have thrown away.

Measured on Lowe's (`lowes/LWS_External_CS/wd5`): **12,424 postings for a blank query, 48 for
`"Bengaluru"`**. That is the difference between ~12,400 requests a poll and ~50, and it is what stopped
this being the most expensive platform here.

Two rules follow:

- **One request per term, unioned by `externalPath`.** The terms are alternatives (`bengaluru` OR
  `bangalore`) and Workday reads a multi-word query conjunctively, so a joined query matches almost
  nothing — on Lowe's the two spellings answer 48 and 3 postings and overlap not at all. The union
  happens before mapping, so a posting matched by two terms still costs one detail call.
- **The query is a prefilter, not the authority.** `searchText` matches description text too, so it
  returns roles that are not in the named place. `JobBoardsConnector.grab` still runs
  `matchesLocation` over the result, which is also why the detail's `location` +
  `additionalLocations` + `country.descriptor` are joined into one string: without the country
  appended, a filter naming "India" would never match a role in Bengaluru.

A site added with **no** locations still walks the whole board, and for a large tenant that is minutes
per poll. Set locations, or a slow schedule, or both. What the location list bounds beyond the fetch is
unchanged: entities, chunks and embeddings.

`postedOn` is prose ("Posted Today"), so `startDate` is the only usable date, and as with Lever and
SmartRecruiters the checksum hashes the body.

#### Oracle HCM is addressed by a pair, and reaches a whole cluster at once

Oracle Recruiting Cloud (`oraclehcm`) exists because of what the other five *cannot* reach. Probing a
real 126-company watchlist, the employers that resolved nowhere split into "runs its own careers stack"
and "runs a hosted ATS nothing here reads" — and the second group was dominated by this one platform:
BNY Mellon, JPMorgan Chase and Kotak Mahindra confirmed, with Akamai and American Express on the same
UI behind vanity domains. One bean reaches all of them, which is why it was worth building ahead of a
per-employer connector for Amazon or Microsoft.

A site is a `host/siteNumber` pair (`eofe.fa.us2.oraclecloud.com/BNY-Careers`) or the career-site URL,
and like Workday **neither part is guessable**: the host is a per-customer Fusion pod whose prefix is
not the company name and whose region segment is sometimes absent (`jpmc.fa.oraclecloud.com`), and the
site number is an arbitrary slug (`CX_1`, `CX_1001`, `BNY-Careers`). So `OracleHcmSite.parse` returning
empty is what tells the connector a bare name is not an Oracle site, and `hasBoard` answers `false`
without a network call.

**A vanity domain is deliberately rejected.** Employers front the pod with their own hostname
(`jobs.akamai.com`, `careers.americanexpress.com`); those serve the UI but **not** the REST API, so
accepting one would produce a site that resolves and then fails every fetch. The underlying pod host
has to be read off the page.

It pays **per posting**, like SmartRecruiters and more so: the listing carries no description at all —
`ShortDescriptionStr` is empty and the qualification fields are null — so every requisition kept costs
a second call. The location terms are therefore sent as Oracle's `keyword` finder, one request per
term, unioned by requisition id. Measured on BNY's site: **1,386 requisitions blank, 138 for `"Pune"`**.
Without that prefilter JPMorgan's 7,325 requisitions would be 7,326 requests a poll.

Two quirks: the search envelope nests one level deeper than the others (`items[0].requisitionList`,
with the count at `items[0].TotalJobsCount`), and the detail finder takes **quoted** values
(`ById;Id="69848",siteNumber="BNY-Careers"`) where the search finder does not. As with Lever,
SmartRecruiters and Workday, no update timestamp is published — `ExternalPostedStartDate` does not move
on an edit — so the checksum hashes the body.


### Filtering what gets kept (`BoardFilter`)

Four independent dimensions, read from `inputs` and carried as one `BoardFilter`: `locations`,
`titleInclude`, `titleExclude`, `maxAgeDays`, `includeRemote`. Empty means *no opinion*, never *match
nothing*.

**Why it matters more than it looks.** Everything kept is parsed, chunked and **embedded**, and hosted
embedding quotas are counted per chunk. Measured across the 71 boards in the console's catalog:

| filter | postings | chunks |
|---|---|---|
| none | 57,389 | ~355,000 |
| location terms (India cities) | 5,124 | ~34,000 |
| + a title include/exclude pair | 1,083 | ~7,400 |

`title` is the strongest lever because it is the one useful field **every platform puts in its
listing**. So a title filter removes the per-posting *detail call* as well as the embedding — Citi goes
from 896 detail requests a poll to 69 — which a location filter often cannot, because a Workday listing
may say only `"5 Locations"`.

**Applied in two places, from one object.** `BoardPlatform.fetch` takes the filter as a *hint*: the
per-posting platforms (SmartRecruiters, Oracle HCM) test `matchesTitle` on the listing before paying
for a detail call, and Workday and Oracle additionally send the place terms as a server-side query.
`JobBoardsConnector.grab` then re-applies the whole filter authoritatively, because a platform is free
to ignore the hint. Both sides call the same `BoardFilter` methods; two copies of the rules would drift,
and a platform filtering harder than the connector would lose postings nobody could account for.

Three rules worth knowing:

- **Exclude beats include.** "Software Engineering Manager" matches an include of `software engineer`
  and an exclude of `manager`; the exclude wins, or the exclude list says nothing.
- **A missing value keeps the posting.** No location, no posted date — any doubt runs it, matching
  `IngestionJob.connectionUnusable`. Boards leave both blank often enough that the alternative loses
  real roles on the strength of an absent field. A *title* is always present, so title terms are exact.
- **`includeRemote` is an OR with the place terms**, not a filter of its own: a stated-remote role
  satisfies `locations` however it is filed, but still has to pass the title and age tests.

The country name alone is usually not enough for `locations` — most boards file a role as `"Bengaluru"`
with no country, so a term list should name cities. And a narrow include list has a real recall cost: on
the measured corpus a backend-flavoured list dropped 1,025 clearly technical roles, including Adobe's
`Computer Scientist` and all 57 of Samsung's silicon roles.

`membershipSignature` covers **every** filter dimension and still excludes `companies`. Each dimension
is a within-iterable membership boundary — tightening one changes which postings of a board survive,
which is a §3.2 re-walk. Leaving one out would mean editing it never re-walks, so the board keeps
whatever it already had.

