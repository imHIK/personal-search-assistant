import { ChevronLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import { cn } from '@/lib/utils'

export function PageHeader({
  title,
  subtitle,
  actions,
  backTo,
  backLabel,
  meta,
  className,
}: {
  title: React.ReactNode
  subtitle?: React.ReactNode
  actions?: React.ReactNode
  backTo?: string
  backLabel?: string
  /** Badges or state pills rendered beside the title. */
  meta?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-6', className)}>
      {backTo && (
        <Link
          to={backTo}
          className="mb-3 inline-flex items-center gap-1 text-sm text-[var(--text-muted)] transition-colors hover:text-[var(--text)]"
        >
          <ChevronLeft className="size-4" aria-hidden />
          {backLabel}
        </Link>
      )}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-1.5">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="truncate text-xl font-semibold tracking-tight text-[var(--text)]">
              {title}
            </h1>
            {meta}
          </div>
          {subtitle && (
            <p className="text-sm leading-relaxed text-[var(--text-muted)]">{subtitle}</p>
          )}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  )
}
