import { http } from './http'

/**
 * Starting an OAuth consent. The provider is a path segment resolved server-side from the registry
 * of `OAuthProvider` beans, so this file needs no change when a second provider is added — only the
 * connector descriptor naming it.
 */
export interface StartOAuthBody {
  /** Connector the account is being connected for, e.g. `GOOGLE_DRIVE`. */
  type: string
  /** Existing account to re-credential. Omit to create a new one. */
  connectionId?: string
  /** Name for a newly created account. */
  name?: string
}

export const oauthApi = {
  /**
   * Returns the provider's consent URL. A JSON response rather than a redirect, so the console
   * decides when to navigate — and so a misconfigured client is an ordinary 400 with a readable
   * reason instead of a bounce to nowhere.
   */
  start: (provider: string, body: StartOAuthBody) =>
    http<{ authorizeUrl: string }>(
      `/api/connections/oauth/${encodeURIComponent(provider)}/start`,
      { method: 'POST', body },
    ),
}

/** Outcome of a consent, read off the query string the callback redirects back with. */
export interface OAuthOutcome {
  status: 'ok' | 'error'
  connectionId?: string
  reason?: string
}

/**
 * Read `?oauth=ok|error&id=…&reason=…` off a location. Returns undefined when the page was not
 * reached from a callback, which is the common case.
 */
export function readOAuthOutcome(search: string): OAuthOutcome | undefined {
  const params = new URLSearchParams(search)
  const outcome = params.get('oauth')
  if (outcome !== 'ok' && outcome !== 'error') return undefined
  return {
    status: outcome,
    connectionId: params.get('id') ?? undefined,
    reason: params.get('reason') ?? undefined,
  }
}
