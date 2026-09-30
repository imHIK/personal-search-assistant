import { http } from './http'
import type { CompanyLookup } from './types'

export const jobBoardsApi = {
  /** A name that matches nothing comes back with `found: false`, not dropped. */
  lookup: (companies: string[]) =>
    http<CompanyLookup[]>('/api/connectors/job-boards/lookup', {
      method: 'POST',
      body: { companies },
    }),
}
