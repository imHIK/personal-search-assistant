import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { searchApi } from '@/api/search'
import type { SearchBody, SearchResult } from '@/api/types'

/**
 * Runs a search and reports when the written answer could not be produced.
 *
 * The server now handles this itself: a failing LLM comes back as 200 with the hits intact and
 * `answerError` set, so the normal path is simply to read that field. It did not always — answer
 * synthesis used to run before the response was built and outside any try/catch, so an unavailable
 * provider 500d the whole request and took the successfully-retrieved hits with it, which to a user
 * looked like "turning on answers broke search".
 *
 * The retry below is kept as a fallback for exactly that older shape (and for any other 500 that the
 * answer flag turns out to trigger): if a request with `answer: true` fails outright, retry once with
 * `answer: false` so the user still gets results plus a banner. Both paths set the same flag.
 */
export function useSearch() {
  const [answerUnavailable, setAnswerUnavailable] = useState(false)

  const mutation = useMutation<SearchResult, unknown, SearchBody>({
    mutationFn: async (body) => {
      setAnswerUnavailable(false)

      if (!body.answer) {
        return searchApi.search(body)
      }

      try {
        const result = await searchApi.search(body)
        if (result.answerError) {
          setAnswerUnavailable(true)
        }
        return result
      } catch (error) {
        // Only worth a retry if the answer flag is what could have broken it — a failure with
        // answers off is a genuine search failure and should surface as one.
        const retried = await searchApi.search({ ...body, answer: false }).catch(() => null)
        if (retried) {
          setAnswerUnavailable(true)
          return retried
        }
        throw error
      }
    },
  })

  return { ...mutation, answerUnavailable }
}
