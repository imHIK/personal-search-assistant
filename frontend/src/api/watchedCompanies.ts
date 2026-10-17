import { http } from './http'
import type { WatchedCompany, WatchedCompanyBody, WatchedCompanyImport } from './types'

const base = '/api/watched-companies'

export const watchedCompaniesApi = {
  list: () => http<WatchedCompany[]>(base),

  /** 409 when the name is taken. */
  create: (body: WatchedCompanyBody) => http<WatchedCompany>(base, { method: 'POST', body }),

  patch: (id: string, body: WatchedCompanyBody) =>
    http<WatchedCompany>(`${base}/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  /** Stamps `lastCheckedAt` with the server's clock. */
  markChecked: (id: string) =>
    http<WatchedCompany>(`${base}/${encodeURIComponent(id)}/checked`, { method: 'POST' }),

  remove: (id: string) => http<null>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** Adds only the bundled companies not already watched. */
  importBundled: () => http<WatchedCompanyImport>(`${base}/import-bundled`, { method: 'POST' }),
}
