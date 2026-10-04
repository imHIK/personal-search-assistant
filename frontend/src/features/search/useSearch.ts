import { useQuery } from '@tanstack/react-query'
import { searchApi } from '@/api/search'
import type { SearchBody, SearchResult } from '@/api/types'

export interface SearchOutcome {
  result: SearchResult
  answerUnavailable: boolean
}

async function run(body: SearchBody): Promise<SearchOutcome> {
  if (!body.answer) {
    return { result: await searchApi.search(body), answerUnavailable: false }
  }
  try {
    const result = await searchApi.search(body)
    return { result, answerUnavailable: Boolean(result.answerError) }
  } catch (error) {
    const retried = await searchApi.search({ ...body, answer: false }).catch(() => null)
    if (retried) {
      return { result: retried, answerUnavailable: true }
    }
    throw error
  }
}

/**
 * Never refetched on its own: returning to a search costs no request and no LLM call. `refetch`
 * re-runs it.
 */
export function useSearch(body: SearchBody | null) {
  return useQuery({
    queryKey: ['search', body],
    queryFn: () => run(body!),
    enabled: body !== null,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnWindowFocus: false,
    retry: false,
  })
}
