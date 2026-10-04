import { http } from './http'
import type { SyncTrigger } from './types'

export const indexingApi = {
  sync: (knowledgeId: string) =>
    http<SyncTrigger>(`/api/index/knowledge/${encodeURIComponent(knowledgeId)}/sync`, {
      method: 'POST',
    }),

  reindexEntity: (entityId: string) =>
    http<null>(`/api/index/entities/${encodeURIComponent(entityId)}/reindex`, { method: 'POST' }),

  /** Tombstones it; its chunks go on a later indexing tick. */
  removeEntity: (entityId: string) =>
    http<null>(`/api/index/entities/${encodeURIComponent(entityId)}`, { method: 'DELETE' }),
}

export const healthApi = {
  check: () => http<{ status: string }>('/q/health'),
}
