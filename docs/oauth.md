# OAuth: connecting accounts

How a user hands this application a credential for a service they own, and how it stays valid.

Two things are described here and they are easy to conflate. The **flow** is ours: a provider-neutral
consent round-trip that turns a browser redirect into stored credentials. The **lifetime** of the
credential it stores is not ours at all — it is a property of how the OAuth application is registered
with the provider, and no amount of code changes it. §1 is the part people get wrong.

---

## 1. Refresh-token lifetime is a provider setting, not a code problem

A refresh token does not renew. Google does not rotate or extend one, and using it does not reset any
clock — so there is no polling interval, no keep-alive and no "refresh the refresh token" that
prolongs it. What decides whether it dies is the OAuth client's registration.

For Google specifically:

| consent screen publishing status | refresh token lifetime |
|---|---|
| **Testing** | **revoked after 7 days**, always |
| **In production** | indefinite |

An application left in Testing therefore breaks roughly weekly, with no signal except data going
stale — which is exactly the failure this document's §4 exists to make visible, and §1 exists to stop
happening at all.

**Publishing is the fix.** Google Cloud Console → APIs & Services → OAuth consent screen → *Publish
app*. Verification is a separate thing and is **not** required for a personal deployment: an
unverified production app works, showing a "Google hasn't verified this app" interstitial on the first
consent (*Advanced* → *Go to … (unsafe)*), and is capped at 100 users.

A published token still dies if the user revokes access, the account goes six months unused, or more
than 100 refresh tokens accumulate for the same user + client pair. Those are real but rare; the
7-day clock is not.

### Registering the client

The flow needs a **Web application** OAuth client (not "Desktop app"), because it receives the code on
a fixed URL rather than a throwaway local port. Register one redirect URI per origin the console
answers on:

```
http://localhost:8080/api/connections/oauth/google/callback
http://localhost:5173/api/connections/oauth/google/callback
```

`:5173` is `frontendDev`, where Vite proxies `/api` to `:8080`. Both exist because the browser stays on
whichever origin it started from — see `app.oauth.allowed-origins` in §3. The client id and secret go
in `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`.

---

## 2. The flow

```
console ──POST /api/connections/oauth/{provider}/start──▶ authorizeUrl
browser ──────────────────────────────────────────────▶ provider's consent screen
provider ─GET /api/connections/oauth/{provider}/callback?code&state─▶ app
app ─────────────────────────────────────303────────────▶ /connections?oauth=ok&id=…
```

**`POST /api/connections/oauth/{provider}/start`** — body `{type, connectionId?, name?}`, where `type` is
a connection type (`GMAIL`, `GOOGLE_DRIVE`, `GMAIL_SEND`), returns
`{authorizeUrl}`. `connectionId` present means re-credential that account; absent means create one.
JSON rather than a redirect, so the console owns the navigation and a misconfigured client surfaces as
an ordinary 400 with a reason rather than a bounce to nowhere.

**`GET /api/connections/oauth/{provider}/callback`** — the only endpoint in the app that answers with a
redirect instead of JSON, because a person is looking at it. Both outcomes land on
`/connections?oauth=ok&id=…` or `?oauth=error&reason=…`.

Credentials are written through `ConnectionService`, not the repository, so the existing
verify-on-auth-change behaviour applies unchanged: a connection that just completed a consent is
proved working against its connector before it is reported back, and its status resets from `ERROR` to
`ACTIVE` in the same write.

### `state`

`OAuthStateStore` mints a single-use token per attempt, holding `{providerId, type, connectionId,
name, redirectUri, returnTo}`. It is the CSRF defence the spec asks for — without it anyone could hand
the callback a code of their choosing — and also the only way the callback knows what it is
completing, since the provider echoes nothing else back. Consuming it is what makes a replayed
callback fail.

It lives **in memory, single-node**, the same accepted trade-off as `InMemoryPermitService`
(invariant 7). Losing it across a restart costs one click on Connect.

### Refresh tokens are never overwritten with nothing

A provider commonly returns a `refresh_token` only on the first consent, and a refresh response almost
never carries one. Both `OAuthTokenService.persist` and `DefaultOAuthConnectService.authBlob` therefore
treat a null as "unchanged", never "clear it" — writing null over a working refresh token turns a
one-hour hiccup into a permanently dead connection. A provider that *does* rotate is handled too: a
non-null value replaces the stored one.

---

### Connection types and scopes

A provider maps **connection types**, not connectors, to scopes: `supports()` and `scopesFor(type)` take
the same string stored as `Connection.type`. For Google:

| connection type | used by | scope |
|---|---|---|
| `GMAIL` | Gmail source | `gmail.readonly` |
| `GOOGLE_DRIVE` | Drive source | `drive.readonly` |
| `GMAIL_SEND` | email channel ([`publishing.md`](./publishing.md)) | `gmail.send` |

Every scope requested must also be listed on the consent screen (*Data access*); a scope that is not
there is silently left out of the grant, and the account then fails its first real call. `GMAIL_SEND`
is the reason this is not keyed by `SourceType`: the sending account belongs to no connector.

## 3. Configuration

| key | meaning |
|---|---|
| `app.oauth.allowed-origins` | console origins a consent may start from and return to |
| `app.oauth.state-ttl-seconds` | how long the user has to finish the consent screen (600) |
| `app.oauth.<provider>.client-id` / `.client-secret` | the registered application, per provider |
| `app.oauth.google.authorize-url` / `.token-url` | Google's endpoints |

`allowed-origins` is an **allow-list, not a suggestion**. The callback redirects the browser to this
value, so echoing back whatever `Origin` the browser claimed would make the endpoint an open redirect,
and would hand the provider a redirect URI it has no reason to trust. An unlisted origin falls back to
the first entry rather than failing — the user still completes the flow, just landing on the canonical
console.

The per-provider client keys are composed by name in `OAuthClients` (`"app.oauth." + id +
".client-id"`) rather than injected, which is what lets a new provider ship two properties and no
resolution code. They are listed in `ConfigDefaultsTest.REACHED_ANOTHER_WAY` for that reason.

A connection may also carry its own `config.clientId` / `config.clientSecret`, which wins over the
app-level pair — two accounts on the same provider can belong to different registered applications.

---

## 4. When a credential does die

`CredentialsRejectedException` is raised when a provider reports the grant is permanently dead
(`invalid_grant` for Google and most others; `AbstractOAuth2Provider.isCredentialsRejected` is the
override point where a vendor deviates). It is deliberately distinct from `OAuthTransportException`,
which is a 5xx or a timeout.

That distinction is the whole point. On rejection, `OAuthTokenService`:

1. drops the cached token — a stale entry would otherwise mask the failure until it expired;
2. marks the connection `ERROR` with a plain-language `lastError`, **unless it is `DISABLED`**, which
   is an operator decision the health sweep also refuses to overwrite;
3. rethrows.

`IngestionJob.connectionUnusable()` then skips that connection's knowledges from the very next tick
rather than burning a lease and a permit to fail, and the console's reconnect banner appears on the
next poll. Previously this took up to `app.connections.health-interval` (30m) to be noticed, and was
visible only to someone who opened the Accounts page and looked.

A transient failure leaves the connection untouched and is retried by the ordinary cursor retry
machinery, exactly as before.

---

## 5. Adding a provider

Everything above is provider-neutral. A second OAuth application is:

1. **One bean.** Extend `AbstractOAuth2Provider` (which already implements the RFC 6749 authorize URL,
   code exchange and refresh) and supply `id()`, `supports()`, `scopesFor(type)`, the two endpoints,
   and any vendor-specific authorize parameters. `GoogleOAuthProvider` is the worked example — its
   whole vendor-specific surface is two URLs, two scopes and three query parameters.
2. **Two properties.** `app.oauth.<id>.client-id` / `.client-secret`, plus an entry in
   `ConfigDefaultsTest.REACHED_ANOTHER_WAY`.
3. **One frontend field.** `oauth: { provider: '<id>' }` on the account's descriptor — a connector in
   `frontend/src/config/connectors.ts`, or a channel's `account` in `frontend/src/config/channels.ts`.

Nothing else. `CdiOAuthProviderRegistry` discovers the bean, `{provider}` in the resource path resolves
through it, and `ConnectAccountButton` renders from the descriptor — no registry entry, no route, no
component branches on a connector type.

`id()` becomes a URL segment, so it is part of the public contract once shipped.

---

## Related

- `docs/connectors.md` — the `SourceConnector` SPI and how a connection is bound to a knowledge
- `docs/configuration.md` — which mechanism a new setting belongs in
- `docs/limitations.md` — L12, the single-node state store
