import { http, query } from './http'
import type { EntitySummary, Task, TaskBody } from './types'

/**
 * The task library — what a digest can be told to do with its results.
 *
 * Built-in tasks come back flagged and are read-only; `duplicate` is how you start from one. The
 * console never distinguishes them by id, only by that flag.
 */
export const tasksApi = {
  list: () => http<Task[]>('/api/tasks'),

  get: (id: string) => http<Task>(`/api/tasks/${id}`),

  create: (body: TaskBody) => http<Task>('/api/tasks', { method: 'POST', body }),

  /**
   * An editable copy, returned but deliberately *not* saved — the user saves it from the editor, so
   * an abandoned duplicate leaves nothing behind.
   */
  duplicate: (id: string) =>
    http<Task>(`/api/tasks${query({ duplicateOf: id })}`, { method: 'POST', body: {} }),

  update: (id: string, body: TaskBody) => http<Task>(`/api/tasks/${id}`, { method: 'PATCH', body }),

  /** 409 when the task is built in, or when a digest still points at it. */
  remove: (id: string) => http<null>(`/api/tasks/${id}`, { method: 'DELETE' }),

  /** Configured LLM profiles, so the editor offers the models that actually exist. */
  llmProfiles: () => http<string[]>('/api/llm-profiles'),

  /** One indexed item, for showing a title where only an entity id is held. */
  entity: (id: string) => http<EntitySummary>(`/api/entities/${id}`),
}
