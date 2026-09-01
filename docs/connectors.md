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
| Greenhouse | `GREENHOUSE` | `ats.greenhouse.GreenhouseConnector` | none (public board) | one per board token |
| Lever | `LEVER` | `ats.lever.LeverConnector` | none (public board) | one per company handle |
| Ashby | `ASHBY` | `ats.ashby.AshbyConnector` | none (public board) | one per board name |

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

## ATS job boards (Greenhouse, Lever, Ashby)

**Inputs** (`knowledge.inputs`): `boards` — a list of board identifiers (or a single string). These
are the handles in `boards.greenhouse.io/<token>`, `jobs.lever.co/<site>` and
`jobs.ashbyhq.com/<name>`. Each becomes one iterable.

All three share `ats.SnapshotBoardConnector`, because all three are **snapshot-shaped**: one request
returns the entire current board. There is no `updated_after` parameter, no continuation token and no
meaningful pagination, so neither `TokenWindowGrabber` nor `TimeWindowGrabber` applies and they
implement `SourceConnector` directly, as `LocalFsConnector` does. `grab` ignores the seed `TimeWindow`
and returns everything in one page with `hasMore=false`.

That sounds like the incremental machinery is wasted, but the expensive half still works: change
detection in `IngestionRunner.persistItem` skips any posting whose checksum is unchanged and is
already `INDEXED`, so a poll costs one HTTP call plus N cheap Mongo lookups.

Two consequences worth knowing:

- **Forward-only.** `supportedDirections()` is `FORWARD` alone. Backward cursors exist to walk history
  below the anchor, and a job board has no history worth walking — a posting old enough to sit below
  the anchor is filled or withdrawn.
- **These are the connectors that opt into retention.** Boards send no tombstone when a role closes;
  it simply stops appearing. `defaultRetention()` returns 14 days against a 3-hour
  `defaultSchedule()` — comfortably longer than the cadence, because an item is re-created by the next
  walk if it still exists, so a short window would only churn re-embeddings. See
  [`knowledge-lifecycle.md`](./knowledge-lifecycle.md).

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
