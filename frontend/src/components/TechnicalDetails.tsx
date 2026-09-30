import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { cn } from '@/lib/utils'
import { labels } from '@/config/labels'

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

export function useTechnicalDetails() {
  return useContext(TechnicalDetailsContext).enabled
}

export function useTechnicalDetailsToggle() {
  return useContext(TechnicalDetailsContext)
}

export function Technical({ children, className }: { children: React.ReactNode; className?: string }) {
  const enabled = useTechnicalDetails()
  if (!enabled) return null
  return <div className={className}>{children}</div>
}

export function TechnicalInline({ children }: { children: React.ReactNode }) {
  const enabled = useTechnicalDetails()
  if (!enabled) return null
  return <>{children}</>
}

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
