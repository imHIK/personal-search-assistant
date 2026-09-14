import { useEffect, useRef, useState } from 'react'
import { CITATION_HIGHLIGHT_MS } from '@/config/constants'

/**
 * Wires `[n]` citation chips to the numbered thing they name.
 *
 * A citation the reader cannot follow is not much better than no citation, and "scroll there" alone
 * loses people when the target looks like everything around it — hence the flash. Shared by the search
 * answer and the digest summary, which cite the same way over two different lists.
 *
 * Targets must be focusable (`tabIndex={-1}`) and carry `scroll-mt-*`, or the jump lands under a
 * sticky header.
 */
export function useCitationJump<E extends HTMLElement = HTMLElement>() {
  const refs = useRef<Record<number, E | null>>({})
  const [citedRank, setCitedRank] = useState<number | null>(null)
  const timer = useRef<number | null>(null)

  // The highlight is a timer, so it has to be cancelled on unmount — otherwise a navigation during
  // the flash sets state on a component that is gone.
  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    },
    [],
  )

  /** Callback ref for rank `n` (1-based, matching what the model writes). */
  const register = (rank: number) => (element: E | null) => {
    refs.current[rank] = element
  }

  /** Scroll to a cited item, move focus there, and flash it so the jump is visible. */
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

  /** Drop the highlight — the ranks stop meaning anything when the list underneath is replaced. */
  const clear = () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
    setCitedRank(null)
  }

  return { citedRank, jumpTo, register, clear }
}
