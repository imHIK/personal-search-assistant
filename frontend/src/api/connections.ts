import { http, query } from './http'
import type {
  Connection,
  CreateConnectionBody,
  PatchConnectionBody,
  SourceType,
} from './types'

/**
 * Read the default flag regardless of which key Jackson emitted for the `boolean isDefault`
 * record component (`isDefault` when treated as a component, `default` when treated as a getter).
 * Reading it through this helper means the UI is correct either way.
 */
export function isDefaultConnection(connection: Connection): boolean {
  return connection.isDefault ?? connection.default ?? false
}

export const connectionsApi = {
  list: (type?: SourceType) => http<Connection[]>('/api/connections' + query({ type })),

  get: (id: string) => http<Connection>(`/api/connections/${encodeURIComponent(id)}`),

  /** Credentials are verified server-side; a rejection comes back as a 400 with the reason. */
  create: (body: CreateConnectionBody) =>
    http<Connection>('/api/connections', { method: 'POST', body }),

  /** Changing `auth` triggers re-verification, so this can also 400. */
  patch: (id: string, body: PatchConnectionBody) =>
    http<Connection>(`/api/connections/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  makeDefault: (id: string) =>
    http<Connection>(`/api/connections/${encodeURIComponent(id)}/default`, { method: 'POST' }),

  /** 409 while any knowledge still binds this connection. */
  remove: (id: string) =>
    http<null>(`/api/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}
