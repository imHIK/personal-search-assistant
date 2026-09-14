import { SlidersHorizontal, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { Toggle } from '@/components/ui/Toggle'
import { labels } from '@/config/labels'
import type { SearchFilterSpec } from '@/config/searchFilters'

interface Props {
  /** Which filters to offer — already narrowed to the current scope by the caller. */
  specs: SearchFilterSpec[]
  values: Record<string, string>
  onChange: (id: string, value: string | null) => void
  onClear: () => void
}

/**
 * Renders whatever `searchFilters.ts` offers for the current scope. Every control is derived from a
 * spec's `kind`, so adding a filter is a descriptor edit and this file never changes.
 *
 * Nothing renders when no spec applies — an empty "Narrow by" panel would suggest the search is
 * missing something rather than that the scope has no facets to narrow on.
 */
export function SearchFilters({ specs, values, onChange, onClear }: Props) {
  if (specs.length === 0) return null

  const active = specs.filter((spec) => values[spec.id])

  return (
    <div className="rounded-xl border border-[var(--border)] bg-[var(--surface-sunken)] px-4 py-3">
      <div className="mb-3 flex items-center gap-2">
        <SlidersHorizontal className="size-3.5 text-[var(--text-subtle)]" aria-hidden />
        <span className="text-xs font-medium text-[var(--text-muted)]">{labels.search.narrowBy}</span>
        {active.length > 0 && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="ml-auto h-6 gap-1 px-2 text-[11px]"
            onClick={onClear}
          >
            <X className="size-3" aria-hidden />
            {labels.search.clearFilters}
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-end gap-3">
        {specs.map((spec) => (
          <label key={spec.id} className="flex flex-col gap-1">
            <span className="text-[11px] text-[var(--text-subtle)]">{spec.label}</span>
            {spec.kind === 'boolean' ? (
              <div className="flex h-8 items-center">
                <Toggle
                  checked={values[spec.id] === '1'}
                  onCheckedChange={(checked) => onChange(spec.id, checked ? '1' : null)}
                  label={spec.label}
                />
              </div>
            ) : spec.kind === 'select' || spec.kind === 'sinceDays' ? (
              <Select
                value={values[spec.id] ?? ''}
                onChange={(event) => onChange(spec.id, event.target.value || null)}
                aria-label={spec.label}
                className="h-8 w-auto min-w-36 text-[13px]"
              >
                <option value="">{labels.search.filterAny}</option>
                {(spec.options ?? []).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            ) : (
              <Input
                value={values[spec.id] ?? ''}
                onChange={(event) => onChange(spec.id, event.target.value || null)}
                placeholder={spec.placeholder}
                aria-label={spec.label}
                inputMode={spec.kind === 'min' ? 'numeric' : undefined}
                className="h-8 w-36 text-[13px]"
              />
            )}
            {spec.hint && (
              <span className="max-w-44 text-[10px] leading-snug text-[var(--text-subtle)]">
                {spec.hint}
              </span>
            )}
          </label>
        ))}
      </div>
    </div>
  )
}
