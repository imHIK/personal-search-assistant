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
| Company job boards | `JOB_BOARDS` | `ats.JobBoardsConnector` | none (public boards) | **one per company**, across Greenhouse / Lever / Ashby / SmartRecruiters / Workday |

## Connections (credentials, separated from knowledges)

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
  not on every knowledge or every grab.
- **Lifecycle & integrity.** `ConnectionService` assigns/re-points the per-type default, blocks
  deleting a connection still bound by a knowledge, and promotes a survivor when the default is
  removed. REST surface: `POST/GET/PATCH/DELETE /api/connections`, plus `POST /api/connections/{id}/default`.

### Google auth

Both Google connectors resolve a bearer token through the shared `GoogleAccessTokens` port
(`DefaultGoogleAccessTokens`), reading the connection's blobs:

| blob | key | meaning |
|---|---|---|
| `auth` | `accessToken` | short-lived bearer token (optional if a refresh token is present) |
| `auth` | `expiresAtEpochSec` | expiry of `accessToken`; treated as expired when absent |
| `auth` | `refreshToken` | long-lived token used to mint new access tokens |
| `config` | `clientId` / `clientSecret` | OAuth client used to refresh (falls back to app config) |

When the access token is missing or near expiry and a refresh token is present, the provider mints a
fresh token from the OAuth endpoint, caches it in-process (keyed by connection id), **and writes it
back onto the connection** so it survives restarts and is shared by every knowledge on that account.
The app-level fallback client is `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` (see
`app.ingestion.google.*`). Swapping in a real secret store is a drop-in replacement of the
`GoogleAccessTokens` / `ConnectionRepository` beans — no connector changes.

Required OAuth scopes: `https://www.googleapis.com/auth/gmail.readonly` and
`https://www.googleapis.com/auth/drive.readonly`.

### Connection health

Credentials are verified at create and on an auth edit — and, until now, never again. A token that
expired afterwards left its connection reading `ACTIVE` while every sync failed: the failures scrolled
past in the log, the console showed nothing wrong, and the first real signal was that the data had
quietly stopped updating.

`ConnectionHealthScheduler` re-runs `verifyConnection` on every connection every
`app.connections.health-interval` (30m) and records the outcome as `ConnectionStatus` + `lastError`.
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
Greenhouse, Lever and Ashby from the listing size, SmartRecruiters from `totalFound`, Workday from
`total`. Throwing it away and re-fetching the board to answer "how big is it?" would double the
requests for a number already in hand, which is what makes the lookup endpoint below cheap.

### Snapshot-shaped

Every supported platform returns the entire current board in one request — no `updated_after`, no
continuation token, no meaningful pagination — so `JobBoardsConnector` implements `SourceConnector`
directly, as `LocalFsConnector` does. The seed `TimeWindow` is ignored and every grab returns one page
with `hasMore=false`. The expensive half of the machinery still works: change detection skips any
posting whose checksum is unchanged and already `INDEXED`.

Backward cursors are not supported — a job board has no history worth walking. A closed posting simply
stops appearing; boards send no tombstone, which is why this connector opts into a 14-day retention
window against a 3-hour poll.

Per-platform quirks:

- **Greenhouse** — `?content=true` makes one call sufficient. `updated_at` is the change signal.
- **Lever** — publishes **no update timestamp**, only `createdAt`. A checksum from `createdAt` alone
  would never move, so an edited posting would be skipped forever (an invariant-3 violation). The
  checksum hashes the body too.
- **Ashby** — the richest: states `isRemote` structurally, publishes real pay bands with
  `includeCompensation`, and is the only one that may carry a close date, which becomes
  `Entity.expiresAt` and beats the knowledge-level retention window.
- **SmartRecruiters** — the only platform that pays **per posting**, and the only one that reaches
  Swiggy (71 postings, 70 in India) and Freshworks. Two consequences below.

#### SmartRecruiters costs 1 + N requests

Its listing carries metadata only — no description — so every posting needs a second call. A board of
N postings therefore costs `1 + N` requests where Greenhouse costs 1, and change detection cannot help:
the runner only skips a posting *after* the connector has produced a `RawItem`, which requires the text.

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

#### Workday is addressed by a triple, and cannot use the hint

A Workday site is a `tenant/site/wd` triple (`adobe/external_experienced/wd5`) or the career-site URL,
and **none of the three parts is guessable** — 13 of 22 blind attempts failed on companies that
certainly use Workday. So `WorkdaySite.parse` returning empty is what tells the connector a bare
company name is not a Workday site, and `hasBoard` answers `false` for one **without any network
call**. Resolution asks every platform about every name; a speculative POST per name would slow adding
companies for no possible benefit.

Search is a `POST` with the paging window in the body — the reason `AtsHttp` has `postJson` — and pages
at 20 against sites holding several hundred (Adobe: 742).

**It deliberately ignores the location hint**, unlike SmartRecruiters. Its search result is
systematically less complete than its detail: a bare city with no country (`"Bengaluru"`), and for a
multi-site role a *count* with no place at all (`"5 Locations"`). Filtering on that would drop a
Bengaluru role whenever the filter names "India", and drop every multi-site role outright — the same
silent miss already measured on Stripe. So everything is fetched and the connector filters the full
locations, which is why the detail's `location` + `additionalLocations` + `country.descriptor` are
joined into one string: without the country appended, a filter naming "India" would never match a role
in Bengaluru.

That makes it the most expensive platform here — `1 + N` over the *whole* site, where SmartRecruiters
pays only for what survives the hint. Add a Workday site deliberately. What the location filter still
bounds is everything downstream: entities, chunks and embeddings.

`postedOn` is prose ("Posted Today"), so `startDate` is the only usable date, and as with Lever and
SmartRecruiters the checksum hashes the body.


### Filtering by location (`inputs.locations`)

Optional list of match terms; empty keeps everything. Applied in `JobBoardsConnector.grab()` rather
than in the platform's `fetch`, because none of these APIs takes a location parameter — the whole board arrives
either way and the saving is entirely downstream, in the upsert, parse, chunk and **embed** that every
surviving posting pays for. Matching is case-insensitive substring against `metadata.location`.

It matters mostly for cost. Measured on the live boards: Databricks carries **857 postings for 92
Indian ones (11%)**, Stripe **592 for 36 (6%)**. Without the filter, roughly nine tenths of everything
ingested is embedded for a country the user will never apply to — and embeddings are the scarcest
resource in the pipeline.

Two behaviours worth knowing:

- **A posting with no location survives.** Boards leave the field blank often enough that dropping
  those would lose real roles on a missing value, and nothing distinguishes an irrelevant location from
  an unstated one. Same rule as `IngestionJob.connectionUnusable`: any doubt runs it. Tombstones are
  never filtered either — dropping one would strand the entity it exists to remove.
- **The country name alone is not enough.** 27 of Stripe's 36 Indian roles are filed as plain
  `Bengaluru` with no country, so `["India"]` keeps 3 of 36 — a 92% silent miss. List the cities. And
  note that `Remote` is blunt: it matches `Remote - US` too, and adding it to Stripe pulled in ~100
  non-Indian roles.

`membershipSignature` covers `locations` and deliberately excludes `companies`. A company is a
discovery-set dimension — each is its own iterable, so adding one is handled by discover-reconcile and
including it would reset every surviving company's cursor on an unrelated edit. `locations` is the
opposite: it moves the membership boundary *inside* each iterable, exactly like `GmailConnector`'s
query, so widening it must re-walk the boards or newly-matching postings are silently never picked up.

Compensation parsing handles Indian notation (Indian digit grouping, `LPA`, lakh, crore) as well as
the western forms, and always records the currency it read — `compMin`/`compMax` are plain numbers in
the index, so a corpus mixing INR and USD would make one numeric filter mean two things. Every match
must be anchored by a currency symbol or a magnitude unit: measured on live boards, an unanchored
pattern read "6-12 months" as six to twelve million and invented a salary band on postings that never
mentioned pay. Expect null far more often than not — see `docs/job-discovery.md` for the measured rate.

Normalisation lives in `ats.AtsNormalization` and is shared, because everything *derived* from the
per-board JSON must be computed identically — most of all `dedupeKey`
(`company|title|location`, normalised), since the same role only collapses across sources if both
sides build the key the same way. The derivations are deliberately conservative: seniority, remoteness
and compensation return null/false rather than guessing, because confidently-wrong metadata makes
filters silently exclude good matches. Where a board states a fact structurally it wins over any
inference — Ashby's `isRemote` and its `includeCompensation` pay bands are used directly.

Per-board quirks:

- **Greenhouse** — `?content=true` is what makes one call sufficient; without it the descriptions need
  a second request per posting. `updated_at` is the change signal.
- **Lever** — publishes **no update timestamp**, only `createdAt`. A checksum built from `createdAt`
  alone would never move, so an edited posting would be skipped forever (an invariant-3 violation).
  The checksum therefore hashes the body as well.
- **Ashby** — the richest of the three, and the only one that can state a close date; when it does,
  that becomes `Entity.expiresAt` and beats the knowledge-level retention window.

## Known limitations

Both Google connectors are list-based, so **source-side deletions are not yet tombstoned** — a
message/file removed after ingestion stays searchable until a re-index. Gmail's push (`watch`) and
Drive's Changes feed would make both incremental *and* deletion-aware; they slot in behind the same
forward-cursor/grab contract when needed.
