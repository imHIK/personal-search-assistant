import { useEffect, useRef, useState } from 'react'
import { CITATION_HIGHLIGHT_MS } from '@/config/constants'

export function useCitationJump<E extends HTMLElement = HTMLElement>() {
  const refs = useRef<Record<number, E | null>>({})
  const [citedRank, setCitedRank] = useState<number | null>(null)
  const timer = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    },
    [],
  )

  /** `rank` is 1-based, as the model writes it. */
  const register = (rank: number) => (element: E | null) => {
    refs.current[rank] = element
  }

  const jumpTo = (rank: number) => {
    const element = refs.current[rank]
    if (!element) return
    // Focus first: focusing during a smooth scroll cancels it, even with preventScroll, so the page
    // would stop partway to the target.
    element.focus({ preventScroll: true })
    element.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setCitedRank(rank)
    if (timer.current !== null) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setCitedRank(null), CITATION_HIGHLIGHT_MS)
  }

  const clear = () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
    setCitedRank(null)
  }

  return { citedRank, jumpTo, register, clear }
}
