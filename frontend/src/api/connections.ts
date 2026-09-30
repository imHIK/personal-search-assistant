import { http, query } from './http'
import type {
  Connection,
  CreateConnectionBody,
  PatchConnectionBody,
} from './types'

export function isDefaultConnection(connection: Connection): boolean {
  return connection.isDefault ?? connection.default ?? false
}

export const connectionsApi = {
  list: (type?: string) => http<Connection[]>('/api/connections' + query({ type })),

  get: (id: string) => http<Connection>(`/api/connections/${encodeURIComponent(id)}`),

  create: (body: CreateConnectionBody) =>
    http<Connection>('/api/connections', { method: 'POST', body }),

  patch: (id: string, body: PatchConnectionBody) =>
    http<Connection>(`/api/connections/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  /** Always 200: bad credentials show as `status` and `lastError` on the returned connection. */
  test: (id: string) =>
    http<Connection>(`/api/connections/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  makeDefault: (id: string) =>
    http<Connection>(`/api/connections/${encodeURIComponent(id)}/default`, { method: 'POST' }),

  /** 409 while any knowledge still binds this connection. */
  remove: (id: string) =>
    http<null>(`/api/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}
