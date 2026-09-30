import { http, query } from './http'
import type { CreateDigestBody, Digest, DigestRun, PatchDigestBody } from './types'

export const digestsApi = {
  list: () => http<Digest[]>('/api/digests'),

  get: (id: string) => http<Digest>(`/api/digests/${id}`),

  create: (body: CreateDigestBody) => http<Digest>('/api/digests', { method: 'POST', body }),

  setEnabled: (id: string, enabled: boolean) =>
    http<Digest>(`/api/digests/${id}`, { method: 'PATCH', body: { enabled } }),

  update: (id: string, body: PatchDigestBody) =>
    http<Digest>(`/api/digests/${id}`, { method: 'PATCH', body }),

  /** Clears the already-seen set; the runs stay. */
  resetHistory: (id: string) =>
    http<Digest>(`/api/digests/${id}/reset-history`, { method: 'POST' }),

  remove: (id: string) => http<null>(`/api/digests/${id}`, { method: 'DELETE' }),

  /** A failed run resolves as a run carrying `error` or `taskError`; it does not throw. */
  run: (id: string) => http<DigestRun>(`/api/digests/${id}/run`, { method: 'POST' }),

  runs: (id: string, limit = 20, offset = 0) =>
    http<DigestRun[]>(`/api/digests/${id}/runs${query({ limit, offset })}`),

  getRun: (id: string, runId: string) => http<DigestRun>(`/api/digests/${id}/runs/${runId}`),
}
