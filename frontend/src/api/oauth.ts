import { http } from './http'

export interface StartOAuthBody {
  type: string
  /** Existing account to re-credential. Omit to create a new one. */
  connectionId?: string
  name?: string
}

export const oauthApi = {
  start: (provider: string, body: StartOAuthBody) =>
    http<{ authorizeUrl: string }>(
      `/api/connections/oauth/${encodeURIComponent(provider)}/start`,
      { method: 'POST', body },
    ),
}

export interface OAuthOutcome {
  status: 'ok' | 'error'
  connectionId?: string
  reason?: string
}

/** Undefined when the page was not reached from an OAuth callback. */
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
