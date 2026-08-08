import { AlertTriangle, type LucideIcon } from 'lucide-react'
import * as React from 'react'
import { Button } from './Button'
import { cn } from '@/lib/utils'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import { useTechnicalDetails } from '@/components/TechnicalDetails'

/** Skeleton rows. Preferred over a spinner for lists so the layout doesn't jump on load. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn('animate-pulse rounded-md bg-[var(--tone-neutral-bg)]', className)}
      aria-hidden
    />
  )
}

export function SkeletonList({ rows = 3, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn('space-y-3', className)} aria-busy="true" aria-live="polite">
      <span className="sr-only">{labels.common.loading}</span>
      {Array.from({ length: rows }).map((_, index) => (
        <div
          key={index}
          className="rounded-xl border border-[var(--border)] bg-[var(--surface)] px-5 py-4"
        >
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="mt-2.5 h-3 w-1/2" />
        </div>
      ))}
    </div>
  )
}

/** Empty state. Always says what to do next, never just "no data". */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: LucideIcon
  title: string
  description?: string
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-xl border border-dashed border-[var(--border)] px-6 py-14 text-center',
        className,
      )}
    >
      {Icon && (
        <div className="mb-3 rounded-full bg-[var(--bg-subtle)] p-3">
          <Icon className="size-5 text-[var(--text-subtle)]" aria-hidden />
        </div>
      )}
      <p className="text-sm font-medium text-[var(--text)]">{title}</p>
      {description && (
        <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-[var(--text-muted)]">
          {description}
        </p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}

/**
 * Error display. Runs the failure through the translation table so the user reads a cause and a
 * fix; the original server message is kept but only shown under Technical details.
 */
export function ErrorState({
  error,
  onRetry,
  className,
  compact,
}: {
  error: unknown
  onRetry?: () => void
  className?: string
  compact?: boolean
}) {
  const friendly = friendlyError(error)
  const technical = useTechnicalDetails()

  return (
    <div
      role="alert"
      className={cn(
        'rounded-xl border border-[var(--tone-alert)]/30 bg-[var(--tone-alert-bg)]',
        compact ? 'px-4 py-3' : 'px-5 py-4',
        className,
      )}
    >
      <div className="flex gap-3">
        <AlertTriangle className="mt-0.5 size-4 shrink-0 text-[var(--tone-alert)]" aria-hidden />
        <div className="min-w-0 flex-1 space-y-1">
          <p className="text-sm font-medium text-[var(--text)]">{friendly.title}</p>
          <p className="text-sm leading-relaxed text-[var(--text-muted)]">{friendly.detail}</p>
          {technical && friendly.raw && friendly.raw !== friendly.detail && (
            <p className="pt-1 font-mono text-[11px] leading-relaxed text-[var(--text-subtle)]">
              {friendly.raw}
            </p>
          )}
          {onRetry && (
            <div className="pt-2">
              <Button size="sm" variant="secondary" onClick={onRetry}>
                {labels.common.retry}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** Horizontal progress bar for "N of M ready to search". */
export function ProgressBar({
  value,
  max,
  tone = 'ok',
  className,
  label,
}: {
  value: number
  max: number
  tone?: 'ok' | 'busy'
  className?: string
  label?: string
}) {
  const percent = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0
  const color = tone === 'ok' ? 'var(--tone-ok)' : 'var(--tone-busy)'

  return (
    <div
      className={cn('h-1.5 w-full overflow-hidden rounded-full bg-[var(--tone-neutral-bg)]', className)}
      role="progressbar"
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-label={label}
    >
      <div
        className="h-full rounded-full transition-[width] duration-500"
        style={{ width: `${percent}%`, backgroundColor: color }}
      />
    </div>
  )
}
