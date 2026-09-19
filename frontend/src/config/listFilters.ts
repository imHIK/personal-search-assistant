import type { EntityStatus } from '@/api/types'
import type { GroupState } from '@/config/presentation'

/**
 * One descriptor per filter offered above a list — the source detail page's mirror of
 * `searchFilters.ts`, and the same bargain: the controls, the URL parameters and the request are
 * all derived from these objects, so offering a new filter is appending one object and no
 * component changes.
 *
 * A spec with a `param` is sent to the API; one without is applied in the browser, which is the
 * honest split between the two lists here — entities are paged server-side (filtering the loaded
 * page would silently search 50 of 4,000 rows), while cursors arrive as one unpaged array.
 */
export type ListFilterKind = 'search' | 'enumMulti' | 'select'

export interface ListFilterSpec {
  /** URL parameter name, and the key into the values map. */
  id: string
  label: string
  kind: ListFilterKind
  /** API query parameter this maps to. Omit for a filter applied client-side. */
  param?: string
  placeholder?: string
  hint?: string
  /**
   * `enumMulti` and `select`. Nothing selected means "no filter", which is how "All" disappears.
   * Options that only exist at runtime (a source's own companies) are left out here and supplied by
   * the caller through {@link withOptions}, so the list itself stays a static descriptor.
   */
  options?: { value: string; label: string }[]
}

/**
 * Values are flat strings so they live in the URL unchanged: an `enumMulti` is a comma-separated
 * list, which is also the wire format the API takes.
 */
export type ListFilterValues = Record<string, string>

const entityStatusOptions: { value: EntityStatus; label: string }[] = [
  { value: 'INGESTED', label: 'Queued' },
  { value: 'INDEXING', label: 'Indexing' },
  { value: 'INDEXED', label: 'Indexed' },
  { value: 'FAILED', label: 'Failed' },
]

export const entityFilters: ListFilterSpec[] = [
  {
    id: 'q',
    param: 'q',
    kind: 'search',
    label: 'Name',
    placeholder: 'Search by name',
  },
  {
    id: 'status',
    param: 'status',
    kind: 'enumMulti',
    label: 'State',
    options: entityStatusOptions,
  },
  {
    // A dropdown, not chips: a job-board source has a hundred companies.
    id: 'group',
    param: 'iterableId',
    kind: 'select',
    label: 'Group',
  },
]

const groupStateOptions: { value: GroupState; label: string }[] = [
  { value: 'syncing', label: 'Syncing' },
  { value: 'queued', label: 'Queued' },
  { value: 'synced', label: 'Synced' },
  { value: 'failed', label: 'Failed' },
]

export const groupFilters: ListFilterSpec[] = [
  {
    id: 'gq',
    kind: 'search',
    label: 'Name',
    placeholder: 'Search groups',
  },
  {
    id: 'gstate',
    kind: 'enumMulti',
    label: 'State',
    options: groupStateOptions,
  },
]

/**
 * Fill in a spec's runtime options — and optionally its label and empty-option text, so a job board's
 * dropdown can say "All companies" — without the descriptor list knowing about any one source.
 */
export function withOptions(
  specs: ListFilterSpec[],
  id: string,
  options: { value: string; label: string }[],
  overrides: Partial<Pick<ListFilterSpec, 'label' | 'placeholder'>> = {},
): ListFilterSpec[] {
  return specs.map((spec) => (spec.id === id ? { ...spec, ...overrides, options } : spec))
}

/** The selected values of one `enumMulti`, as a list. */
export function selectedValues(values: ListFilterValues, id: string): string[] {
  const raw = values[id]
  return raw ? raw.split(',').filter(Boolean) : []
}

/** Add or remove one option of an `enumMulti`, preserving the rest. */
export function toggleValue(values: ListFilterValues, id: string, option: string): string {
  const current = selectedValues(values, id)
  const next = current.includes(option)
    ? current.filter((value) => value !== option)
    : [...current, option]
  return next.join(',')
}

/** True when anything is set, so an empty result can say "nothing matches" rather than "nothing yet". */
export function hasActiveFilters(specs: ListFilterSpec[], values: ListFilterValues): boolean {
  return specs.some((spec) => Boolean(values[spec.id]))
}

/**
 * The API parameters for the specs that have one. Client-side specs are skipped rather than sent:
 * the backend would ignore an unknown parameter, which reads as "my filter did nothing".
 */
export function buildListQuery(
  specs: ListFilterSpec[],
  values: ListFilterValues,
): Record<string, string> {
  const params: Record<string, string> = {}
  for (const spec of specs) {
    const raw = values[spec.id]
    if (!spec.param || !raw) continue
    params[spec.param] = raw
  }
  return params
}
