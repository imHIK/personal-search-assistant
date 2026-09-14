# Publishing

How the application sends a message to a person: an email today, a WhatsApp group or a Slack channel
later. Digests are the first producer, but nothing here knows about them — see *Digests*.

Publishing is a separate flow from ingestion. A connector *reads* a corpus; a publisher *writes* a
message to a `Channel`. They have separate types (`SourceType` / `ChannelType`) and separate SPIs. What
they can share is an **account**: a `Connection` is typed by a *connection type* rather than a
`SourceType`, so the email channel sends through a Google account of its own (`GMAIL_SEND`) using the same
OAuth flow, token refresh, health sweep and reconnect banner a Gmail source uses — see *Accounts*.

---

## Shape

```
POST /api/channels/{id}/publish ─▶ PublishingService.enqueue ─▶ deliveries (PENDING, message snapshot)

PublishingScheduler tick ─▶ DeliveryRunner
    ready? (channel usable, account resolvable and ACTIVE)
    claimNext (lease) ─▶ Publisher.publish(channel, connection, message, deliveryId)
                     ─▶ markSent | markRetry | markFailed   (all lease-fenced)
```

| Record | Collection | What it is |
|---|---|---|
| `Channel` | `channels` | a destination: `type`, optional `connectionId`, an opaque `target` blob, `enabled`, `status` / `lastError` |
| `PublishMessage` | embedded | what to say: plain `title`, `intro`, `items[]`, `link`, and an optional Markdown `summary` |
| `Delivery` | `deliveries` | one message on its way to one channel — a row in the outbox |

## Why an outbox

Enqueueing writes a row and returns; a worker sends it. Doing the send inside the caller would make a
slow or unreachable platform the caller's problem — a digest run would fail or stall because Gmail was
down. With the row in between:

- a transient failure is a retry, not a lost message;
- a delivery stores a **snapshot** of its message, so a retry sends what was queued and the history
  shows what went out;
- "send it again" is a status flip on a row that already holds the content;
- `dedupeKey` makes enqueueing idempotent. A second enqueue with the same key returns the first
  delivery. Manual sends carry none; a digest run uses `digestRun:<runId>:<channelId>`, so a replayed
  run cannot send twice. The unique index is partial (string keys only), so nulls never collide.

## The worker

`DeliveryRunner.runOnce` claims **one delivery at a time**, up to `app.publishing.batch`. Claiming a
batch up front would start every lease together; a slow first send would then let the last delivery's
lease lapse while it was still queued, and a second worker could send it too.

**Only ready channels are claimed.** A channel is ready when it is enabled and `ACTIVE`, and — for a
publisher that sends through an account — that account resolves and is itself `ACTIVE`. After each
claim readiness is checked again: if something changed since the tick started, the delivery is released
without an attempt.

The two ways a channel can be not-ready are handled differently, on purpose:

| cause | what happens | how it recovers |
|---|---|---|
| no account connected, or the named one is gone or of the wrong type | channel → `ERROR` with the reason | a person fixes it, then a **test** |
| the account exists but is `ERROR` (rejected sign-in) or `DISABLED` | skipped; channel untouched | **reconnecting the account** releases the queue on the next tick |

The second is derived, never stored — the same stance `IngestionJob.connectionUnusable()` takes. Parking
the channel instead would leave it `ERROR` after the reconnect, waiting for a test nobody knows to run.

Every post-claim write is compare-and-set on `lease.owner` + an unexpired lease, the same fence
entities and cursors use (invariant 2). A `false` means another worker owns the row, and the caller
stops.

### Failures

A publisher throws `PublishException(permanent, …)`; anything else counts as transient.

| failure | example | delivery | channel |
|---|---|---|---|
| transient | timeout, 429, 5xx, Gmail per-user rate limit, rejected sign-in | `PENDING`, `attempts+1`, `nextAttemptAt` = backoff | unchanged |
| permanent | Gmail 400 (a recipient it will not accept), 403 missing permission | `PENDING`, `attempts+1` | **`ERROR`** with `lastError` |
| `attempts` reaches `retry-limit` | either | **`FAILED`** (dead-letter) | — |

A permanent failure parks the *channel* rather than dead-lettering the *message*. The refusal is almost
always about the channel, so every other message queued for it would fail the same way. Parking stops
them burning attempts, and once the fault is fixed a successful **test**
(`POST /api/channels/{id}/test`) returns the channel to `ACTIVE` and the whole queue flows, including the
message that tripped it. A message that is genuinely undeliverable still reaches `FAILED` after
`retry-limit` rounds of that.

A rejected sign-in is transient for the reason in the table above: `OAuthTokenService` has already
marked the *account* `ERROR`, and the readiness check is what holds the queue.

Backoff doubles from `backoff-seconds` and is capped at `max-backoff-seconds`. Attempts are
**consecutive** (invariant 9): `markSent` resets them, and `FAILED` is a real dead-letter that nothing
reclaims — `POST /api/deliveries/{id}/retry` is the only way back.

### At-least-once

A worker that crashes after the platform accepted a message, but before `markSent` lands, leaves the row
claimable when its lease expires, and the next worker sends it again. The same happens if a send
outlives `app.publishing.lease-seconds`. See [L13](./limitations.md).

Email gets no mitigation. `users.messages.send` takes no idempotency key and **replaces any
`Message-ID` the message carries with its own** (checked against a real send), so a resent delivery
arrives as a second email. Checking the Sent folder first would need a read scope, which is exactly what
the send-only account exists to avoid.

## Accounts

A `Connection`'s `type` is a **connection type**, not a `SourceType`. `ConnectionKindRegistry` knows every
type something is installed to use, from two places:

- every `SourceConnector` that `requiresConnection()` — a kind named after its `SourceType`, verified by
  the connector's own `verifyConnection`. So `GMAIL` and `GOOGLE_DRIVE` resolve exactly as before, with
  no data migration: Mongo already stored the enum name;
- `ConnectionKind` beans — for accounts nothing in the knowledge flow uses. `GmailSendConnectionKind`
  (`GMAIL_SEND`) is the first.

A publisher declares the type it sends through (`Publisher.connectionType()`), and
`ChannelConnectionResolver` picks the account: the channel's `connectionId` if set, else that type's
default. An account of the wrong type is refused, never used. Deleting a connection is a 409 while a
channel names it.

`GMAIL_SEND` is deliberately its own type rather than a second scope on `GMAIL`: the account ingestion
reads with can never send, and the account that sends can never read.

## The SPI

```java
public interface Publisher {
    ChannelType type();
    default Optional<String> connectionType() { return Optional.empty(); }
    void validateTarget(Map<String, Object> target);                  // no network; → 400
    default void verify(Channel channel, Connection connection) {}   // before a test send
    PublishReceipt publish(Channel channel, Connection connection, PublishMessage message, String reference);
}
```

A publisher owns everything platform-shaped: the contents of `target`, rendering, and classifying
failures. The outbox, leases, retries, channel status and account resolution belong to the framework;
`connection` is null for a publisher that declares no connection type.

`PublishMessage` carries no markup because every platform renders differently — HTML for email, a
length-capped text for WhatsApp, blocks for Slack. A pre-rendered body would suit one of them and have to
be taken apart by the rest.

### Adding a platform

1. A `ChannelType` constant (`SLACK` and `WHATSAPP` are already declared).
2. One `@ApplicationScoped` bean implementing `Publisher`. `CdiPublisherRegistry` discovers it; creating
   a channel of a type with no bean is a 400 until it exists.
3. If it sends as a user: return a connection type from `connectionType()`, add a `ConnectionKind` bean
   for it, and map its scopes in the OAuth provider (see [`oauth.md`](./oauth.md)).
4. The frontend descriptor in `frontend/src/config/channels.ts`: flip `implemented`, add `targetFields`,
   and an `account` descriptor if it sends through one — the Accounts screen then offers it too.

> **WhatsApp groups:** Meta's official Cloud API cannot post to groups — only to individual numbers
> that opted in. A group target means a provider such as Twilio, or an unofficial bridge. Decide that
> before writing the bean.

## Email

`EmailPublisher` sends through the **Gmail API** (`users.messages.send`) as a `GMAIL_SEND` account.

```jsonc
// channel
{ "type": "EMAIL", "connectionId": null,   // null = the default GMAIL_SEND account
  "target": { "to": ["you@example.com"], "cc": ["someone@example.com"], "subjectPrefix": "[assistant]" } }
```

`to` may be a single string or a list. Address validation is deliberately loose — it catches a pasted
wrong field, and real refusals come back from Gmail. There is no `from`: Gmail sends as the account.

**Why not SMTP.** SMTP to Gmail needs an app password, which skips two-step verification, never expires
and opens the whole mailbox over IMAP to whoever holds it. The `gmail.send` scope can send and nothing
else, is revocable per app from the Google account, and its access token lives an hour.

`EmailRenderer` builds an inline-styled HTML body (Gmail strips `<style>`) plus a plain-text alternative.
Message content comes from indexed documents and model replies, so every string is escaped, only
`http(s)` URIs become links, and the subject is flattened to one line (a newline in a header is header
injection).

Excerpts are tidied before rendering (`EmailRenderer.clean`): extraction keeps whatever the source had,
and a Google Doc exports each empty paragraph as a `\r\n`, so one title can arrive followed by dozens of
blank lines. Line endings are normalised, invisible characters such as a byte-order mark are dropped
(zero-width joiners stay — Indic scripts and emoji need them), and runs of blank lines collapse to one.
Single line breaks are kept, so a list still reads line by line. The layout itself is nested
presentation tables with inline styles — what renders the same in Gmail and Outlook. Vert.x's `MailEncoder` turns that into an RFC 2822 `multipart/alternative` message — header
encoding and line lengths are exactly the part not worth hand-rolling — which is sent base64url-encoded.

A message's `summary` is Markdown (a digest task's reply). It is rendered with commonmark-java with raw
HTML escaped and non-web link targets dropped — model output over indexed content gets no more trust than
the rest of the message — and kept as written in the text part.

**Verifying the account.** `users.getProfile` refuses a token holding only `gmail.send`, which is the point
of the scope, so `GmailSendConnectionKind` asks Google's `tokeninfo` endpoint which scopes the token
carries. That catches an account connected before the scope was added to the consent screen: it refreshes
happily and then fails every send. The token travels in the POST body, never a URL.

`GmailSendFailures` classifies: 400/404 and a 403 missing-permission are permanent; a 403
`rateLimitExceeded`, 401, 429, 5xx, transport failures and a rejected sign-in are transient.

### Setting it up

1. **Consent screen scope** (once): Google Cloud Console → OAuth consent screen → *Data access* → add
   `https://www.googleapis.com/auth/gmail.send`. The Gmail API is already enabled for the Gmail source.
2. **Publishing status**: the consent screen must be *In production*, or Google revokes the refresh token
   after 7 days ([`oauth.md`](./oauth.md) §1). Unverified is fine for a personal deployment.
3. **Connect the account**: console → Accounts → add *Gmail (sending)* → Connect with Google.
4. **Create the channel**: Channels → add → *Send to* your address → Send test.

Mail from your own account to your own address lands in the inbox, and a Gmail filter on the subject
prefix can label it.

## Configuration

| key | default | meaning |
|---|---|---|
| `app.publishing.poll-interval` | `30s` | worker tick — the most a new message waits for its first attempt |
| `app.publishing.batch` | `20` | deliveries sent per tick |
| `app.publishing.lease-seconds` | `120` | per-delivery claim; must exceed the slowest single send |
| `app.publishing.retry-limit` | `5` | consecutive failures before `FAILED` |
| `app.publishing.backoff-seconds` | `60` | first retry delay, doubling |
| `app.publishing.max-backoff-seconds` | `3600` | cap on the delay |
| `app.publishing.gmail.base-url` | `https://gmail.googleapis.com/gmail/v1` | Gmail API |
| `app.publishing.gmail.tokeninfo-url` | `https://oauth2.googleapis.com/tokeninfo` | scope check |
| `app.publishing.gmail.timeout-seconds` | `30` | per request; keep below `lease-seconds` |
| `app.console.url` | `http://localhost:8080` | base of the link a published digest carries back to the console |

The OAuth client is the one the Google sources use (`GOOGLE_OAUTH_CLIENT_ID` / `_SECRET`).

## API

| Endpoint | Effect |
|---|---|
| `GET /api/channels` | list, newest first |
| `POST /api/channels` | create; 400 for an unknown type, a type with no publisher, a target the publisher refuses, or a `connectionId` that is missing or of the wrong type |
| `GET /api/channels/{id}` | read one |
| `PATCH /api/channels/{id}` | edit `name` / `connectionId` / `target` / `enabled`; absent or null = unchanged; `"connectionId": ""` switches back to the default account |
| `POST /api/channels/{id}/test` | synchronous sample send; **200 either way** — read `status` / `lastError` |
| `POST /api/channels/{id}/publish` | queue a `PublishMessage`; **202** with the delivery |
| `DELETE /api/channels/{id}` | delete, cascading its deliveries; 409 while a digest sends to it |
| `GET /api/deliveries` | newest first; `?channelId=`, `?refId=` (a digest run id), `?status=`, `?limit=` (≤100), `?offset=` |
| `GET /api/deliveries/{id}` | one delivery |
| `POST /api/deliveries/{id}/retry` | `FAILED` → `PENDING`, attempts reset; 409 otherwise |
| `POST /api/connections/oauth/google/start` | with `{"type": "GMAIL_SEND"}`, connects a sending account |

A disabled or `ERROR` channel still **accepts** a publish. The message waits in the queue: pausing a
channel or fixing its account should not lose what was sent to it in the meantime.

## Digests

A digest's `channelIds` publishes its runs: a run that found something or failed is queued once per
channel with origin `DIGEST_RUN` / the run id and dedupe key `digestRun:<runId>:<channelId>`. The rules,
and why a quiet run sends nothing, are in [`digests.md`](./digests.md#sending-results-to-channels).
