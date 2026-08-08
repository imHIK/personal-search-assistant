import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { searchApi } from '@/api/search'
import type { SearchBody, SearchResult } from '@/api/types'

/**
 * Runs a search, with one important piece of damage control.
 *
 * `POST /api/search` synthesizes the answer server-side *before* responding, so when the LLM
 * provider is missing or failing the whole request 500s — and the hits, which were retrieved
 * successfully, are lost with it. To a user that looks like "turning on answers broke search".
 *
 * So: if a request with `answer: true` fails, retry once with `answer: false`. The user still
 * gets their results, plus a banner explaining why there is no written answer.
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
        return await searchApi.search(body)
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
