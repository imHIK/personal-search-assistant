import { Search, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { FacetValue, Knowledge } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { Toggle } from '@/components/ui/Toggle'
import { jobDashboard, NO_STATUS, type JobFilterSpec, type JobParams } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { cn } from '@/lib/utils'

interface JobFiltersProps {
  specs: JobFilterSpec[]
  values: JobParams
  facets: Record<string, FacetValue[]> | undefined
  sources: Knowledge[]
  onChange: (id: string, value: string) => void
  onClear: () => void
  mySkills: string
  onMySkillsChange: (value: string) => void
}

export function JobFilters({
  specs,
  values,
  facets,
  sources,
  onChange,
  onClear,
  mySkills,
  onMySkillsChange,
}: JobFiltersProps) {
  const active = Object.keys(values).some((key) => key !== 'page' && values[key] !== '')

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-[var(--text-subtle)]">
          {labels.jobs.filters}
        </h2>
        {active && (
          <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-[11px]" onClick={onClear}>
            <X className="size-3" aria-hidden />
            {labels.jobs.clear}
          </Button>
        )}
      </div>

      {specs.map((spec) => (
        <FilterControl
          key={spec.id}
          spec={spec}
          values={values}
          facetValues={spec.path ? facets?.[spec.path] : undefined}
          onChange={onChange}
        />
      ))}

      {sources.length > 1 && (
        <Group label={labels.jobs.source}>
          <Chips
            options={sources.map((k) => ({ value: k.id, label: k.name }))}
            selected={split(values.source)}
            onChange={(next) => onChange('source', next.join(','))}
          />
        </Group>
      )}

      <Group label={labels.jobs.status}>
        <Chips
          options={[
            ...jobDashboard.statuses.map((s) => ({ value: s.value, label: s.label })),
            { value: NO_STATUS, label: labels.jobs.noStatus },
          ]}
          selected={split(values.status)}
          onChange={(next) => onChange('status', next.join(','))}
        />
      </Group>

      <Toggle
        checked={values.showHidden === '1'}
        onCheckedChange={(checked) => onChange('showHidden', checked ? '1' : '')}
        label={labels.jobs.showHidden}
      />

      <div className="space-y-1.5 border-t border-[var(--border)] pt-4">
        <p className="text-xs font-medium text-[var(--text-muted)]">{labels.jobs.mySkills}</p>
        <DebouncedInput
          value={mySkills}
          onChange={onMySkillsChange}
          placeholder={labels.jobs.mySkillsPlaceholder}
          label={labels.jobs.mySkills}
        />
        <p className="text-[11px] text-[var(--text-subtle)]">{labels.jobs.mySkillsHint}</p>
      </div>
    </div>
  )
}

function FilterControl({
  spec,
  values,
  facetValues,
  onChange,
}: {
  spec: JobFilterSpec
  values: JobParams
  facetValues: FacetValue[] | undefined
  onChange: (id: string, value: string) => void
}) {
  const value = values[spec.id] ?? ''
  switch (spec.kind) {
    case 'search':
    case 'contains':
      return (
        <Group label={spec.label}>
          <DebouncedInput
            value={value}
            onChange={(next) => onChange(spec.id, next)}
            placeholder={spec.placeholder}
            label={spec.label}
            icon={spec.kind === 'search'}
          />
        </Group>
      )
    case 'toggle':
      return (
        <Toggle
          checked={value === '1'}
          onCheckedChange={(checked) => onChange(spec.id, checked ? '1' : '')}
          label={spec.label}
        />
      )
    case 'sinceDays':
    case 'tri':
      return (
        <Group label={spec.label}>
          <Select
            value={value}
            onChange={(event) => onChange(spec.id, event.target.value)}
            aria-label={spec.label}
            className="h-8 text-[13px]"
          >
            <option value="">{labels.jobs.any}</option>
            {(spec.options ?? []).map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </Group>
      )
    case 'range':
      return (
        <Group label={spec.label}>
          <div className="flex items-center gap-2">
            <DebouncedInput
              value={values[`${spec.id}_min`] ?? ''}
              onChange={(next) => onChange(`${spec.id}_min`, next)}
              placeholder={labels.jobs.min}
              label={`${spec.label} ${labels.jobs.min}`}
              type="number"
            />
            <DebouncedInput
              value={values[`${spec.id}_max`] ?? ''}
              onChange={(next) => onChange(`${spec.id}_max`, next)}
              placeholder={labels.jobs.max}
              label={`${spec.label} ${labels.jobs.max}`}
              type="number"
            />
          </div>
        </Group>
      )
    case 'multi': {
      const options = spec.options ?? groupFacets(facetValues ?? [], spec.labelFor)
      if (options.length === 0 && !value) return null
      return (
        <Group label={spec.label}>
          <Chips
            options={options}
            selected={split(value)}
            onChange={(next) => onChange(spec.id, next.join(','))}
          />
        </Group>
      )
    }
  }
}

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-[var(--text-muted)]">{label}</p>
      {children}
    </div>
  )
}

/** `values` set: one chip standing for several stored spellings of the same thing. */
interface ChipOption {
  value: string
  label: string
  count?: number
  values?: string[]
}

function groupFacets(facets: FacetValue[], labelFor?: (value: string) => string): ChipOption[] {
  const groups = new Map<string, ChipOption>()
  for (const facet of facets) {
    const raw = String(facet.value)
    const label = labelFor ? labelFor(raw) : raw
    const group = groups.get(label)
    if (group) {
      group.values!.push(raw)
      group.count = (group.count ?? 0) + facet.count
    } else {
      groups.set(label, { value: raw, label, count: facet.count, values: [raw] })
    }
  }
  return [...groups.values()].sort((a, b) => (b.count ?? 0) - (a.count ?? 0))
}

function Chips({
  options,
  selected,
  onChange,
}: {
  options: ChipOption[]
  selected: string[]
  onChange: (next: string[]) => void
}) {
  const valuesOf = (o: ChipOption) => o.values ?? [o.value]
  // A selected value that fell out of the facet list must stay visible, or it could not be removed.
  const shown: ChipOption[] = [
    ...options,
    ...selected
      .filter((v) => !options.some((o) => valuesOf(o).includes(v)))
      .map((v) => ({ value: v, label: v })),
  ]
  return (
    <div className="flex max-h-48 flex-wrap gap-1 overflow-y-auto">
      {shown.map((option) => {
        const values = valuesOf(option)
        const active = values.every((v) => selected.includes(v))
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() =>
              onChange(
                active
                  ? selected.filter((v) => !values.includes(v))
                  : [...selected, ...values.filter((v) => !selected.includes(v))],
              )
            }
            className={cn(
              'rounded-full border px-2.5 py-1 text-[11px] font-medium transition-colors',
              active
                ? 'border-[var(--accent)] bg-[var(--accent-subtle)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text)]',
            )}
          >
            {option.label}
            {option.count !== undefined && (
              <span className="ml-1 tabular-nums text-[var(--text-subtle)]">{option.count}</span>
            )}
          </button>
        )
      })}
    </div>
  )
}

function DebouncedInput({
  value,
  onChange,
  placeholder,
  label,
  icon,
  type = 'text',
}: {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  label: string
  icon?: boolean
  type?: 'text' | 'number'
}) {
  const [draft, setDraft] = useState(value)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  // Re-syncs only when the incoming value differs, so it never fights the user mid-keystroke.
  useEffect(() => {
    setDraft((current) => (current === value ? current : value))
  }, [value])

  useEffect(() => {
    if (draft === value) return
    const timer = setTimeout(() => onChangeRef.current(draft), 300)
    return () => clearTimeout(timer)
  }, [draft, value])

  return (
    <div className="relative w-full">
      {icon && (
        <Search
          className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-[var(--text-subtle)]"
          aria-hidden
        />
      )}
      <Input
        type={type}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={placeholder}
        aria-label={label}
        className={cn('h-8 w-full text-[13px]', icon && 'pl-8')}
      />
    </div>
  )
}

function split(value: string | undefined): string[] {
  return value ? value.split(',').filter(Boolean) : []
}
