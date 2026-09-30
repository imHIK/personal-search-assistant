import { Plus, X } from 'lucide-react'
import type { RateLimitPolicy, RateLimitRule } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { labels } from '@/config/labels'

const UNITS: { seconds: number; label: string }[] = [
  { seconds: 1, label: labels.accounts.rateLimitUnits.second },
  { seconds: 60, label: labels.accounts.rateLimitUnits.minute },
  { seconds: 3600, label: labels.accounts.rateLimitUnits.hour },
  { seconds: 86400, label: labels.accounts.rateLimitUnits.day },
]

function split(windowSeconds: number): { count: number; unit: number } {
  for (const unit of [...UNITS].reverse()) {
    if (windowSeconds % unit.seconds === 0) {
      return { count: windowSeconds / unit.seconds, unit: unit.seconds }
    }
  }
  return { count: windowSeconds, unit: 1 }
}

export function RateLimitFields({
  value,
  onChange,
  disabled,
}: {
  value: RateLimitPolicy
  onChange: (next: RateLimitPolicy) => void
  disabled?: boolean
}) {
  const rules = value.rules
  const update = (next: RateLimitRule[]) => onChange({ rules: next })
  const patch = (index: number, rule: Partial<RateLimitRule>) =>
    update(rules.map((r, i) => (i === index ? { ...r, ...rule } : r)))

  return (
    <div className="space-y-3">
      {rules.length === 0 && (
        <p className="text-xs text-[var(--text-muted)]">{labels.accounts.rateLimitEmpty}</p>
      )}

      {rules.map((rule, index) => {
        const { count, unit } = split(rule.windowSeconds)
        return (
          <div key={index} className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1">
              <span className="text-xs text-[var(--text-muted)]">{labels.accounts.rateLimitCount}</span>
              <Input
                type="number"
                min={1}
                className="w-28"
                value={rule.permits}
                disabled={disabled}
                onChange={(event) =>
                  patch(index, { permits: Math.max(1, Number(event.target.value) || 1) })
                }
              />
            </label>

            <label className="flex flex-col gap-1">
              <span className="text-xs text-[var(--text-muted)]">{labels.accounts.rateLimitPer}</span>
              <Input
                type="number"
                min={1}
                className="w-20"
                value={count}
                disabled={disabled}
                onChange={(event) =>
                  patch(index, {
                    windowSeconds: Math.max(1, Number(event.target.value) || 1) * unit,
                  })
                }
              />
            </label>

            <Select
              className="w-32"
              value={unit}
              disabled={disabled}
              onChange={(event) => patch(index, { windowSeconds: count * Number(event.target.value) })}
            >
              {UNITS.map((option) => (
                <option key={option.seconds} value={option.seconds}>
                  {option.label}
                </option>
              ))}
            </Select>

            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={disabled}
              aria-label={labels.accounts.rateLimitRemove}
              onClick={() => update(rules.filter((_, i) => i !== index))}
            >
              <X />
            </Button>
          </div>
        )
      })}

      <Button
        type="button"
        variant="ghost"
        size="sm"
        disabled={disabled}
        onClick={() => update([...rules, { permits: 60, windowSeconds: 60 }])}
      >
        <Plus />
        {labels.accounts.rateLimitAdd}
      </Button>
    </div>
  )
}
