import { http, query } from './http'
import type { Task, TaskBody } from './types'

export const tasksApi = {
  list: () => http<Task[]>('/api/tasks'),

  get: (id: string) => http<Task>(`/api/tasks/${id}`),

  create: (body: TaskBody) => http<Task>('/api/tasks', { method: 'POST', body }),

  /** Returns an editable copy without saving it. */
  duplicate: (id: string) =>
    http<Task>(`/api/tasks${query({ duplicateOf: id })}`, { method: 'POST', body: {} }),

  update: (id: string, body: TaskBody) => http<Task>(`/api/tasks/${id}`, { method: 'PATCH', body }),

  /** 409 when the task is built in, or when a digest still points at it. */
  remove: (id: string) => http<null>(`/api/tasks/${id}`, { method: 'DELETE' }),

  llmProfiles: () => http<string[]>('/api/llm-profiles'),
}
