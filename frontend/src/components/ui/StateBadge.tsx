import { cn } from '@/lib/utils'
import type { Presented, Tone } from '@/config/presentation'
import { useTechnicalDetails } from '@/components/TechnicalDetails'

/**
 * The only status pill in the app. It takes an already-translated {@link Presented} rather than a
 * raw enum, so the translation stays in config/presentation.ts and every surface tells the user
 * the same story with the same four colours.
 */

const toneStyles: Record<Tone, string> = {
  ok: 'bg-[var(--tone-ok-bg)] text-[var(--tone-ok)]',
  busy: 'bg-[var(--tone-busy-bg)] text-[var(--tone-busy)]',
  wait: 'bg-[var(--tone-wait-bg)] text-[var(--tone-wait)]',
  alert: 'bg-[var(--tone-alert-bg)] text-[var(--tone-alert)]',
  neutral: 'bg-[var(--tone-neutral-bg)] text-[var(--tone-neutral)]',
}

const dotStyles: Record<Tone, string> = {
  ok: 'bg-[var(--tone-ok)]',
  busy: 'bg-[var(--tone-busy)] animate-pulse-dot',
  wait: 'bg-[var(--tone-wait)]',
  alert: 'bg-[var(--tone-alert)]',
  neutral: 'bg-[var(--tone-neutral)]',
}

interface StateBadgeProps {
  state: Presented
  size?: 'sm' | 'md'
  className?: string
}

export function StateBadge({ state, size = 'md', className }: StateBadgeProps) {
  const technical = useTechnicalDetails()

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full font-medium',
        toneStyles[state.tone],
        size === 'sm' ? 'px-2 py-0.5 text-[11px]' : 'px-2.5 py-1 text-xs',
        className,
      )}
      title={state.hint}
    >
      <span className={cn('rounded-full', dotStyles[state.tone], size === 'sm' ? 'size-1.5' : 'size-2')} />
      {state.label}
      {technical && state.raw && state.raw !== state.label && (
        <span className="font-mono text-[10px] opacity-60">{state.raw}</span>
      )}
    </span>
  )
}
