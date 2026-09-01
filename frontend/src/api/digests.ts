import { http, query } from './http'
import type { CreateDigestBody, Digest, DigestRun } from './types'

export const digestsApi = {
  list: () => http<Digest[]>('/api/digests'),

  get: (id: string) => http<Digest>(`/api/digests/${id}`),

  create: (body: CreateDigestBody) => http<Digest>('/api/digests', { method: 'POST', body }),

  setEnabled: (id: string, enabled: boolean) =>
    http<Digest>(`/api/digests/${id}`, { method: 'PATCH', body: { enabled } }),

  remove: (id: string) => http<null>(`/api/digests/${id}`, { method: 'DELETE' }),

  /**
   * Run now rather than waiting for the schedule. A search or task failure comes back as a run
   * carrying `error` — the run happened, it just failed — so this resolves rather than throwing.
   */
  run: (id: string) => http<DigestRun>(`/api/digests/${id}/run`, { method: 'POST' }),

  runs: (id: string, limit = 20) =>
    http<DigestRun[]>(`/api/digests/${id}/runs${query({ limit })}`),
}
