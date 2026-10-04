import * as Menu from '@radix-ui/react-dropdown-menu'
import { Check, ChevronDown, X } from 'lucide-react'
import { jobDashboard, statusFor } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { cn } from '@/lib/utils'

// Literal class names, so Tailwind sees them.
const PILL_TONES: Record<string, string> = {
  ok: 'border-[var(--tone-ok)]/40 bg-[var(--tone-ok-bg)] text-[var(--tone-ok)]',
  busy: 'border-[var(--tone-busy)]/40 bg-[var(--tone-busy-bg)] text-[var(--tone-busy)]',
  wait: 'border-[var(--tone-wait)]/40 bg-[var(--tone-wait-bg)] text-[var(--tone-wait)]',
  alert: 'border-[var(--tone-alert)]/40 bg-[var(--tone-alert-bg)] text-[var(--tone-alert)]',
}

const DOT_TONES: Record<string, string> = {
  ok: 'bg-[var(--tone-ok)]',
  busy: 'bg-[var(--tone-busy)]',
  wait: 'bg-[var(--tone-wait)]',
  alert: 'bg-[var(--tone-alert)]',
}

const ITEM =
  'flex h-8 cursor-default select-none items-center gap-2.5 rounded-md px-2 text-[13px] text-[var(--text)] ' +
  'outline-none data-[highlighted]:bg-[var(--surface-hover)]'

export function StatusMenu({
  value,
  disabled,
  onChange,
}: {
  value: unknown
  disabled?: boolean
  onChange: (next: string | null) => void
}) {
  const status = statusFor(value)

  return (
    <Menu.Root modal={false}>
      <Menu.Trigger
        disabled={disabled}
        className={cn(
          'inline-flex h-7 items-center gap-1.5 rounded-full border pl-2.5 pr-2 text-[12px] font-medium',
          'outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--accent)]/40',
          'disabled:opacity-50',
          status
            ? PILL_TONES[status.tone]
            : 'border-dashed border-[var(--border-strong)] text-[var(--text-muted)] hover:bg-[var(--surface-hover)] hover:text-[var(--text)]',
        )}
      >
        {status && <span className={cn('size-1.5 rounded-full', DOT_TONES[status.tone])} aria-hidden />}
        {status?.label ?? labels.jobs.setStatus}
        <ChevronDown className="size-3.5 opacity-70" aria-hidden />
      </Menu.Trigger>

      <Menu.Portal>
        <Menu.Content
          align="end"
          sideOffset={6}
          className="z-40 min-w-44 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-1 shadow-[var(--shadow-lg)]"
        >
          <Menu.Label className="px-2 pb-1 pt-1.5 text-[11px] font-medium text-[var(--text-subtle)]">
            {labels.jobs.status}
          </Menu.Label>
          <Menu.RadioGroup value={status?.value ?? ''} onValueChange={(next) => onChange(next)}>
            {jobDashboard.statuses.map((option) => (
              <Menu.RadioItem key={option.value} value={option.value} className={ITEM}>
                <span className={cn('size-2 rounded-full', DOT_TONES[option.tone])} aria-hidden />
                <span className="flex-1">{option.label}</span>
                <Menu.ItemIndicator>
                  <Check className="size-3.5 text-[var(--text-muted)]" aria-hidden />
                </Menu.ItemIndicator>
              </Menu.RadioItem>
            ))}
          </Menu.RadioGroup>
          {status && (
            <>
              <Menu.Separator className="my-1 h-px bg-[var(--border)]" />
              <Menu.Item className={cn(ITEM, 'text-[var(--text-muted)]')} onSelect={() => onChange(null)}>
                <X className="size-3.5" aria-hidden />
                {labels.jobs.clearStatus}
              </Menu.Item>
            </>
          )}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}
