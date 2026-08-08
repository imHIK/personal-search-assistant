import { http } from './http'
import type { SyncTrigger } from './types'

export const indexingApi = {
  /** Re-arms this knowledge's forward cursors now (IDLE → AVAILABLE). */
  sync: (knowledgeId: string) =>
    http<SyncTrigger>(`/api/index/knowledge/${encodeURIComponent(knowledgeId)}/sync`, {
      method: 'POST',
    }),

  /** Flags one entity for re-indexing. No source re-fetch — the raw payload is retained. */
  reindexEntity: (entityId: string) =>
    http<null>(`/api/index/entities/${encodeURIComponent(entityId)}/reindex`, { method: 'POST' }),

  /** Tombstones one entity; the indexing stage removes its chunks on a later tick. */
  removeEntity: (entityId: string) =>
    http<null>(`/api/index/entities/${encodeURIComponent(entityId)}`, { method: 'DELETE' }),
}

export const healthApi = {
  check: () => http<{ status: string }>('/q/health'),
}
