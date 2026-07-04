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

## Known limitations

Both Google connectors are list-based, so **source-side deletions are not yet tombstoned** — a
message/file removed after ingestion stays searchable until a re-index. Gmail's push (`watch`) and
Drive's Changes feed would make both incremental *and* deletion-aware; they slot in behind the same
forward-cursor/grab contract when needed.
