import { http, query } from './http'
import type {
  CreateKnowledgeBody,
  CursorInfo,
  EntityPage,
  EntityStatus,
  Knowledge,
  PatchKnowledgeBody,
} from './types'

export const knowledgeApi = {
  list: () => http<Knowledge[]>('/api/knowledge'),

  get: (id: string) => http<Knowledge>(`/api/knowledge/${encodeURIComponent(id)}`),

  /**
   * Note: this resolves with **200 even when activation failed** — the returned knowledge then
   * carries `status: 'ERROR'` and a `lastError`. Callers must check `status`, not just the promise.
   */
  create: (body: CreateKnowledgeBody) =>
    http<Knowledge>('/api/knowledge', { method: 'POST', body }),

  patch: (id: string, body: PatchKnowledgeBody) =>
    http<Knowledge>(`/api/knowledge/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  pause: (id: string) =>
    http<null>(`/api/knowledge/${encodeURIComponent(id)}/pause`, { method: 'POST' }),

  resume: (id: string) =>
    http<null>(`/api/knowledge/${encodeURIComponent(id)}/resume`, { method: 'POST' }),

  remove: (id: string) =>
    http<null>(`/api/knowledge/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  entities: (
    id: string,
    params: { status?: EntityStatus | null; limit?: number; offset?: number } = {},
  ) =>
    http<EntityPage>(
      `/api/knowledge/${encodeURIComponent(id)}/entities` +
        query({ status: params.status, limit: params.limit, offset: params.offset }),
    ),

  cursors: (id: string) =>
    http<CursorInfo[]>(`/api/knowledge/${encodeURIComponent(id)}/cursors`),
}
