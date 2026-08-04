import { http } from './http'
import type { SearchBody, SearchResult } from './types'

export const searchApi = {
  /**
   * A single blocking request — there is no streaming endpoint, and with `answer: true` the LLM
   * call happens server-side before anything comes back.
   *
   * Caution: when `answer` is true and the LLM provider is unconfigured or failing, the **whole**
   * request 500s and the hits are lost with it. `useSearch` retries once without the answer flag
   * so search still works; see src/features/search.
   */
  search: (body: SearchBody, signal?: AbortSignal) =>
    http<SearchResult>('/api/search', { method: 'POST', body, signal }),
}
