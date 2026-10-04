import { http } from './http'
import type { SearchBody, SearchResult } from './types'

export const searchApi = {
  search: (body: SearchBody, signal?: AbortSignal) =>
    http<SearchResult>('/api/search', { method: 'POST', body, signal }),
}
