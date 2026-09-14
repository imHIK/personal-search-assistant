import { http, query } from './http'
import type { CreateDigestBody, Digest, DigestRun, PatchDigestBody } from './types'

export const digestsApi = {
  list: () => http<Digest[]>('/api/digests'),

  get: (id: string) => http<Digest>(`/api/digests/${id}`),

  create: (body: CreateDigestBody) => http<Digest>('/api/digests', { method: 'POST', body }),

  setEnabled: (id: string, enabled: boolean) =>
    http<Digest>(`/api/digests/${id}`, { method: 'PATCH', body: { enabled } }),

  /** Any subset of fields; anything omitted is left alone, and the run history always survives. */
  update: (id: string, body: PatchDigestBody) =>
    http<Digest>(`/api/digests/${id}`, { method: 'PATCH', body }),

  /**
   * Forget what has already been reported, keeping the recorded runs. The next run may repeat things
   * already seen — which is the point after widening a query.
   */
  resetHistory: (id: string) =>
    http<Digest>(`/api/digests/${id}/reset-history`, { method: 'POST' }),

  remove: (id: string) => http<null>(`/api/digests/${id}`, { method: 'DELETE' }),

  /**
   * Run now rather than waiting for the schedule. A search or task failure comes back as a run
   * carrying `error` — the run happened, it just failed — so this resolves rather than throwing.
   */
  run: (id: string) => http<DigestRun>(`/api/digests/${id}/run`, { method: 'POST' }),

  runs: (id: string, limit = 20, offset = 0) =>
    http<DigestRun[]>(`/api/digests/${id}/runs${query({ limit, offset })}`),

  /** One run by id, so a link into the history resolves without paging to find it. */
  getRun: (id: string, runId: string) => http<DigestRun>(`/api/digests/${id}/runs/${runId}`),
}
