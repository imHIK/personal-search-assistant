import { useQuery } from '@tanstack/react-query'
import { searchApi } from '@/api/search'
import type { SearchBody, SearchResult } from '@/api/types'

export interface SearchOutcome {
  result: SearchResult
  /** Asked for an answer and did not get one; the hits are intact. */
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
    // Only worth a retry if the answer flag is what could have broken it — a failure with answers off
    // is a genuine search failure and should surface as one.
    const retried = await searchApi.search({ ...body, answer: false }).catch(() => null)
    if (retried) {
      return { result: retried, answerUnavailable: true }
    }
    throw error
  }
}

/**
 * Cached per request for the whole session and never refetched on its own, so returning to a search
 * costs no request and no LLM call. `refetch` is the explicit re-run.
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
