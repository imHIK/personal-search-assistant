import { useState } from 'react'
import { Field, Input, Select } from '@/components/ui/Input'
import { parseDuration, retentionUnits, type RetentionUnit } from '@/config/constants'
import { labels } from '@/config/labels'

/**
 * An empty amount sends null (inherit). The unit is kept locally, so clearing the amount keeps the
 * chosen unit.
 */
export function RetentionField({
  value,
  onChange,
  inherited,
}: {
  value: string
  onChange: (value: string) => void
  inherited?: string
}) {
  const parsed = parseDuration(value)
  const [localUnit, setLocalUnit] = useState<RetentionUnit>(parsed?.unit ?? 'd')
  const unit = parsed?.unit ?? localUnit
  const amount = parsed ? String(parsed.amount) : ''

  const emit = (nextAmount: string, nextUnit: RetentionUnit) => {
    const n = Number(nextAmount)
    onChange(nextAmount !== '' && Number.isInteger(n) && n > 0 ? `${n}${nextUnit}` : '')
  }

  return (
    <Field
      label={labels.settings.retentionPeriod}
      hint={labels.settings.retentionHint(inherited)}
      htmlFor="retention-amount"
    >
      <div className="flex gap-2">
        <Input
          id="retention-amount"
          type="number"
          min={1}
          step={1}
          inputMode="numeric"
          value={amount}
          className="w-32"
          onChange={(event) => emit(event.target.value, unit)}
        />
        <Select
          aria-label={labels.settings.retentionUnit}
          value={unit}
          className="w-auto"
          onChange={(event) => {
            const nextUnit = event.target.value as RetentionUnit
            setLocalUnit(nextUnit)
            if (amount) emit(amount, nextUnit)
          }}
        >
          {retentionUnits.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
      </div>
    </Field>
  )
}
