import { Technical } from '@/components/TechnicalDetails'
import { Field, Input, Select } from '@/components/ui/Input'
import {
  schedulePresets,
  type SchedulePresetValue,
} from '@/config/constants'
import { labels } from '@/config/labels'

export interface ScheduleValue {
  preset: SchedulePresetValue | 'custom'
  cron: string
}

/**
 * Check-frequency picker. Presents plain choices ("Every hour") and maps them onto the
 * `interval` + `scheduleEnabled` pair the API takes; `cron` is offered only under Technical
 * details, since it is an expert escape hatch rather than a user decision.
 *
 * Note the API cannot clear `cron`/`interval` back to inherited — null means "unchanged" — so
 * this deliberately never offers a "reset to default" option it could not honour.
 */
export function ScheduleField({
  value,
  onChange,
}: {
  value: ScheduleValue
  onChange: (value: ScheduleValue) => void
}) {
  return (
    <div className="space-y-4">
      <Field
        label={labels.wizard.stepSchedule}
        hint="How often to look for new items. Checking more often costs nothing but a little CPU."
        required
        htmlFor="schedule-preset"
      >
        <Select
          id="schedule-preset"
          value={value.preset}
          onChange={(event) =>
            onChange({ ...value, preset: event.target.value as ScheduleValue['preset'] })
          }
        >
          {schedulePresets.map((preset) => (
            <option key={preset.value} value={preset.value}>
              {preset.label}
            </option>
          ))}
          <option value="custom">{labels.schedule.custom}</option>
        </Select>
      </Field>

      {value.preset === 'custom' && (
        <Field
          label="Cron expression"
          hint="Standard 5-field cron. Takes precedence over any interval."
          required
          htmlFor="schedule-cron"
        >
          <Input
            id="schedule-cron"
            value={value.cron}
            placeholder="0 */2 * * *"
            className="font-mono text-[13px]"
            onChange={(event) => onChange({ ...value, cron: event.target.value })}
          />
        </Field>
      )}

      <Technical>
        <p className="text-xs leading-relaxed text-[var(--text-subtle)]">
          {labels.settings.scheduleCannotClear}
        </p>
      </Technical>
    </div>
  )
}

/** Turn the picker's value into the flat schedule fields the API expects. */
export function scheduleToBody(value: ScheduleValue) {
  if (value.preset === 'custom') {
    return { cron: value.cron.trim() || null, interval: null, scheduleEnabled: true }
  }
  const preset = schedulePresets.find((p) => p.value === value.preset)
  return {
    cron: null,
    interval: preset?.interval ?? null,
    scheduleEnabled: preset?.enabled ?? false,
  }
}
