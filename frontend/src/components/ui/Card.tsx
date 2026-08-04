import * as React from 'react'
import { cn } from '@/lib/utils'

/** Forwards its ref so callers can scroll a card into view (search citations do exactly this). */
export const Card = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement> & { interactive?: boolean }
>(function Card({ className, interactive, ...props }, ref) {
  return (
    <div
      ref={ref}
      className={cn(
        'rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-[var(--shadow-sm)]',
        interactive && 'transition-colors hover:border-[var(--border-strong)] hover:bg-[var(--surface-hover)]',
        className,
      )}
      {...props}
    />
  )
})

export function CardHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('border-b border-[var(--border)] px-5 py-4', className)} {...props} />
}

export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-sm font-semibold text-[var(--text)]', className)} {...props} />
}

export function CardBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('px-5 py-4', className)} {...props} />
}

/** A single headline number with its label. Used for the Searchable/Processing/Failed tiles. */
export function StatTile({
  label,
  value,
  tone = 'neutral',
  onClick,
  hint,
}: {
  label: string
  value: string | number
  tone?: 'ok' | 'busy' | 'alert' | 'neutral'
  onClick?: () => void
  hint?: string
}) {
  const toneColor = {
    ok: 'text-[var(--tone-ok)]',
    busy: 'text-[var(--tone-busy)]',
    alert: 'text-[var(--tone-alert)]',
    neutral: 'text-[var(--text)]',
  }[tone]

  const Wrapper = onClick ? 'button' : 'div'

  return (
    <Wrapper
      onClick={onClick}
      type={onClick ? 'button' : undefined}
      title={hint}
      className={cn(
        'rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-3.5 text-left',
        onClick && 'transition-colors hover:border-[var(--border-strong)] hover:bg-[var(--surface-hover)]',
      )}
    >
      <p className={cn('text-2xl font-semibold tabular-nums', toneColor)}>{value}</p>
      <p className="mt-0.5 text-xs text-[var(--text-muted)]">{label}</p>
    </Wrapper>
  )
}
