import { http } from './http'
import type { CompanyLookup } from './types'

export const jobBoardsApi = {
  /**
   * Resolve candidate company names to the platform hosting each, without creating anything.
   * A name that matches nothing comes back with `found: false` rather than being dropped.
   */
  lookup: (companies: string[]) =>
    http<CompanyLookup[]>('/api/connectors/job-boards/lookup', {
      method: 'POST',
      body: { companies },
    }),
}
