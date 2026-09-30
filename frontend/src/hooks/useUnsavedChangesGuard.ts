import { useCallback, useEffect, useRef, useState } from 'react'
import { useBlocker } from 'react-router-dom'

/**
 * Covers three exits: in-app navigation (`useBlocker`), tab close or reload (`beforeunload`, with
 * the browser's own prompt), and in-page resets (wrap them in `guard(action)`). Call
 * `allowNavigation()` right before a navigation that is the save: the form is still dirty then.
 */
export function useUnsavedChangesGuard(dirty: boolean) {
  const bypass = useRef(false)
  const [pending, setPending] = useState<(() => void) | null>(null)

  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && !bypass.current && currentLocation.pathname !== nextLocation.pathname,
  )

  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const guard = useCallback(
    (action: () => void) => {
      if (dirty) setPending(() => action)
      else action()
    },
    [dirty],
  )

  const allowNavigation = useCallback(() => {
    bypass.current = true
  }, [])

  const blocked = blocker.state === 'blocked'

  return {
    guard,
    allowNavigation,
    dialogProps: {
      open: blocked || pending !== null,
      onOpenChange: (open: boolean) => {
        if (open) return
        if (blocked) blocker.reset()
        setPending(null)
      },
      onConfirm: () => {
        if (blocked) blocker.proceed()
        pending?.()
        setPending(null)
      },
    },
  }
}
