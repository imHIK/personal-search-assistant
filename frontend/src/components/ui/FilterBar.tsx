import { Search, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { labels } from '@/config/labels'
import {
  hasActiveFilters,
  selectedValues,
  toggleValue,
  type ListFilterSpec,
  type ListFilterValues,
} from '@/config/listFilters'
import { cn } from '@/lib/utils'

interface FilterBarProps {
  specs: ListFilterSpec[]
  values: ListFilterValues
  onChange: (id: string, value: string) => void
  onClear: () => void
}

/**
 * Renders whatever a `ListFilterSpec[]` offers. Every control comes from a spec's `kind`, so a new
 * filter is a descriptor edit and this file never changes — the same contract `SearchFilters` has
 * on the search page.
 *
 * Typing is debounced here rather than at each call site: the entity list refetches on every value
 * change, and a request per keystroke would both flood the API and make the list flicker.
 */
export function FilterBar({ specs, values, onChange, onClear }: FilterBarProps) {
  if (specs.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-2">
      {specs.map((spec) =>
        spec.kind === 'search' ? (
          <SearchFilter
            key={spec.id}
            spec={spec}
            value={values[spec.id] ?? ''}
            onChange={(value) => onChange(spec.id, value)}
          />
        ) : spec.kind === 'select' ? (
          <SelectFilter
            key={spec.id}
            spec={spec}
            value={values[spec.id] ?? ''}
            onChange={(value) => onChange(spec.id, value)}
          />
        ) : (
          <ChipGroup
            key={spec.id}
            spec={spec}
            values={values}
            onChange={(value) => onChange(spec.id, value)}
          />
        ),
      )}

      {hasActiveFilters(specs, values) && (
        <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-[11px]" onClick={onClear}>
          <X className="size-3" aria-hidden />
          {labels.filters.clear}
        </Button>
      )}
    </div>
  )
}

function SearchFilter({
  spec,
  value,
  onChange,
}: {
  spec: ListFilterSpec
  value: string
  onChange: (value: string) => void
}) {
  const [draft, setDraft] = useState(value)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  // Re-sync when the value changes from outside (Clear, or a back/forward navigation), without
  // fighting the user mid-keystroke: this only runs when the incoming value actually differs.
  useEffect(() => {
    setDraft((current) => (current === value ? current : value))
  }, [value])

  useEffect(() => {
    if (draft === value) return
    const timer = setTimeout(() => onChangeRef.current(draft), 300)
    return () => clearTimeout(timer)
  }, [draft, value])

  return (
    <div className="relative">
      <Search
        className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-[var(--text-subtle)]"
        aria-hidden
      />
      <Input
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={spec.placeholder}
        aria-label={spec.label}
        className="h-8 w-56 pl-8 text-[13px]"
      />
    </div>
  )
}

/**
 * A single choice from a long list. Hidden while it has nothing to offer, and kept visible when the
 * current value is not among the options (a link to a group whose cursors have not loaded yet) so
 * an active filter is never invisible.
 */
function SelectFilter({
  spec,
  value,
  onChange,
}: {
  spec: ListFilterSpec
  value: string
  onChange: (value: string) => void
}) {
  const options = spec.options ?? []
  if (options.length === 0 && !value) return null
  const known = options.some((option) => option.value === value)

  return (
    <Select
      value={value}
      onChange={(event) => onChange(event.target.value)}
      aria-label={spec.label}
      className="h-8 w-auto min-w-40 max-w-64 text-[13px]"
    >
      <option value="">{spec.placeholder ?? labels.filters.all(spec.label)}</option>
      {!known && value && <option value={value}>{value}</option>}
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </Select>
  )
}

/**
 * Multi-select chips rather than one-of tabs. Nothing selected is the "All" the tabs used to have,
 * which is why there is no All chip to keep in sync with the others.
 */
function ChipGroup({
  spec,
  values,
  onChange,
}: {
  spec: ListFilterSpec
  values: ListFilterValues
  onChange: (value: string) => void
}) {
  const selected = selectedValues(values, spec.id)

  return (
    <div className="flex flex-wrap items-center gap-1" role="group" aria-label={spec.label}>
      {(spec.options ?? []).map((option) => {
        const active = selected.includes(option.value)
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(toggleValue(values, spec.id, option.value))}
            className={cn(
              'rounded-full border px-2.5 py-1 text-[11px] font-medium transition-colors',
              active
                ? 'border-[var(--accent)] bg-[var(--accent-subtle)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text)]',
            )}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}
