import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { cn } from '@/lib/utils'
import { labels } from '@/config/labels'

/**
 * The single escape hatch for everything a user shouldn't have to see.
 *
 * The domain model is full of machinery — ids, checksums, cursor positions, leases, generations,
 * raw enum names, RRF scores. Hiding it outright would make the app undebuggable; showing it makes
 * the app unusable. So it is all rendered through this one toggle, off by default and persisted.
 *
 * Rule of thumb for what goes behind it: if a non-technical user could not act on it, it is
 * technical.
 */

const STORAGE_KEY = 'psa.technical-details'

const TechnicalDetailsContext = createContext<{
  enabled: boolean
  toggle: () => void
}>({ enabled: false, toggle: () => {} })

export function TechnicalDetailsProvider({ children }: { children: React.ReactNode }) {
  const [enabled, setEnabled] = useState(() => localStorage.getItem(STORAGE_KEY) === 'true')

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, String(enabled))
  }, [enabled])

  const toggle = useCallback(() => setEnabled((value) => !value), [])
  const value = useMemo(() => ({ enabled, toggle }), [enabled, toggle])

  return (
    <TechnicalDetailsContext.Provider value={value}>{children}</TechnicalDetailsContext.Provider>
  )
}

/** True when the user has asked to see internals. */
export function useTechnicalDetails() {
  return useContext(TechnicalDetailsContext).enabled
}

export function useTechnicalDetailsToggle() {
  return useContext(TechnicalDetailsContext)
}

/** Renders its children only when the toggle is on. */
export function Technical({ children, className }: { children: React.ReactNode; className?: string }) {
  const enabled = useTechnicalDetails()
  if (!enabled) return null
  return <div className={className}>{children}</div>
}

/** Inline variant, for a single value tucked next to something user-facing. */
export function TechnicalInline({ children }: { children: React.ReactNode }) {
  const enabled = useTechnicalDetails()
  if (!enabled) return null
  return <>{children}</>
}

/**
 * A labelled block of raw key/value data. Used for cursor positions, checksums, index rollups —
 * anything that is a debugging aid rather than an answer to a user's question.
 */
export function TechnicalPanel({
  title = labels.common.technical,
  rows,
  className,
}: {
  title?: string
  rows: [string, React.ReactNode][]
  className?: string
}) {
  const enabled = useTechnicalDetails()
  if (!enabled || rows.length === 0) return null

  return (
    <div
      className={cn(
        'rounded-lg border border-dashed border-[var(--border)] bg-[var(--bg-subtle)] p-3',
        className,
      )}
    >
      <p className="mb-2 text-[11px] font-medium uppercase tracking-wide text-[var(--text-subtle)]">
        {title}
      </p>
      <dl className="grid gap-x-6 gap-y-1.5 text-xs sm:grid-cols-[max-content_1fr]">
        {rows.map(([key, value]) => (
          <div key={key} className="contents">
            <dt className="text-[var(--text-subtle)]">{key}</dt>
            <dd className="break-all font-mono text-[11px] text-[var(--text-muted)]">
              {value ?? '—'}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
