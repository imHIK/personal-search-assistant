import { useCallback, useEffect, useRef, useState } from 'react'
import { useBlocker } from 'react-router-dom'

/**
 * Asks before throwing away a half-filled form.
 *
 * Three ways out need covering, and no single mechanism reaches all of them:
 * - **In-app navigation** (back links, sidebar, Cancel, the browser back button) goes through the
 *   data router, so `useBlocker` holds it until the user answers.
 * - **Tab close / reload** never reaches the router; `beforeunload` is the only hook, and browsers
 *   show their own prompt there — a custom dialog is not allowed.
 * - **In-page resets** ("Change type") are plain state changes the router never sees; wrap them in
 *   `guard(action)`.
 *
 * Call `allowNavigation()` right before a navigation that *is* the save (e.g. to the created record) —
 * the form is still dirty at that moment and would otherwise block its own success path. It is a ref,
 * not state, because the navigate happens in the same tick, before any re-render.
 *
 * Render the result as `<ConfirmDialog {...guard.dialogProps} …copy… />`.
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
