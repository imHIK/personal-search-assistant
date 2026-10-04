import type { EntityStatus } from '@/api/types'
import type { GroupState } from '@/config/presentation'

export type ListFilterKind = 'search' | 'enumMulti' | 'select'

export interface ListFilterSpec {
  id: string
  label: string
  kind: ListFilterKind
  /** Omit for a filter applied client-side. */
  param?: string
  placeholder?: string
  hint?: string
  /**
   * `enumMulti` and `select` only; nothing selected means no filter. Runtime options are supplied
   * through {@link withOptions}.
   */
  options?: { value: string; label: string }[]
}

/** An `enumMulti` value is a comma-separated list, which is also the API's wire format. */
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

export function withOptions(
  specs: ListFilterSpec[],
  id: string,
  options: { value: string; label: string }[],
  overrides: Partial<Pick<ListFilterSpec, 'label' | 'placeholder'>> = {},
): ListFilterSpec[] {
  return specs.map((spec) => (spec.id === id ? { ...spec, ...overrides, options } : spec))
}

export function selectedValues(values: ListFilterValues, id: string): string[] {
  const raw = values[id]
  return raw ? raw.split(',').filter(Boolean) : []
}

export function toggleValue(values: ListFilterValues, id: string, option: string): string {
  const current = selectedValues(values, id)
  const next = current.includes(option)
    ? current.filter((value) => value !== option)
    : [...current, option]
  return next.join(',')
}

export function hasActiveFilters(specs: ListFilterSpec[], values: ListFilterValues): boolean {
  return specs.some((spec) => Boolean(values[spec.id]))
}

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
