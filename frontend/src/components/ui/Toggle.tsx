import * as RadixSwitch from '@radix-ui/react-switch'
import { cn } from '@/lib/utils'

export function Toggle({
  checked,
  onCheckedChange,
  label,
  hint,
  disabled,
  className,
}: {
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  label: string
  hint?: string
  disabled?: boolean
  className?: string
}) {
  return (
    <label
      className={cn(
        'flex cursor-pointer items-start gap-3',
        disabled && 'cursor-not-allowed opacity-60',
        className,
      )}
    >
      <RadixSwitch.Root
        checked={checked}
        onCheckedChange={onCheckedChange}
        disabled={disabled}
        className="mt-0.5 h-5 w-9 shrink-0 rounded-full bg-[var(--border-strong)] transition-colors data-[state=checked]:bg-[var(--accent)]"
      >
        <RadixSwitch.Thumb className="block size-4 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[18px]" />
      </RadixSwitch.Root>
      <span className="min-w-0 space-y-0.5">
        <span className="block text-sm font-medium text-[var(--text)]">{label}</span>
        {hint && <span className="block text-xs leading-relaxed text-[var(--text-subtle)]">{hint}</span>}
      </span>
    </label>
  )
}

/** Segmented control. Used for search style and item filters — fewer clicks than a dropdown. */
export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  className,
  ariaLabel,
}: {
  value: T
  onChange: (value: T) => void
  options: { value: T; label: string; hint?: string; badge?: React.ReactNode }[]
  className?: string
  ariaLabel?: string
}) {
  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      className={cn(
        'inline-flex items-center gap-0.5 rounded-lg border border-[var(--border)] bg-[var(--bg-subtle)] p-0.5',
        className,
      )}
    >
      {options.map((option) => {
        const selected = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            title={option.hint}
            onClick={() => onChange(option.value)}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-[6px] px-3 py-1.5 text-[13px] font-medium transition-colors',
              selected
                ? 'bg-[var(--surface)] text-[var(--text)] shadow-[var(--shadow-sm)]'
                : 'text-[var(--text-muted)] hover:text-[var(--text)]',
            )}
          >
            {option.label}
            {option.badge}
          </button>
        )
      })}
    </div>
  )
}
