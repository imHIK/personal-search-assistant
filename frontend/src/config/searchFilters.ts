import type { Blob, SourceType } from '@/api/types'

export type SearchFilterKind = 'term' | 'select' | 'boolean' | 'min' | 'sinceDays'

export interface SearchFilterSpec {
  id: string
  field: string
  kind: SearchFilterKind
  label: string
  hint?: string
  placeholder?: string
  /** `select` and `sinceDays` only. */
  options?: { value: string; label: string }[]
  sourceTypes?: SourceType[]
}

const jobBoards: SourceType[] = ['JOB_BOARDS']

export const seniorityOptions: { value: string; label: string }[] = [
  { value: 'INTERN', label: 'Intern' },
  { value: 'JUNIOR', label: 'Junior' },
  { value: 'SENIOR', label: 'Senior' },
  { value: 'STAFF', label: 'Staff' },
  { value: 'PRINCIPAL', label: 'Principal' },
  { value: 'LEAD', label: 'Lead' },
  { value: 'LEADERSHIP', label: 'Leadership' },
]

export const searchFilters: SearchFilterSpec[] = [
  {
    id: 'company',
    field: 'metadata.company',
    kind: 'term',
    label: 'Company',
    placeholder: 'Acme',
    sourceTypes: jobBoards,
  },
  {
    id: 'location',
    field: 'metadata.location',
    kind: 'term',
    label: 'Location',
    placeholder: 'Berlin',
    sourceTypes: jobBoards,
  },
  {
    id: 'remote',
    field: 'metadata.remote',
    kind: 'boolean',
    label: 'Remote only',
    sourceTypes: jobBoards,
  },
  {
    id: 'seniority',
    field: 'metadata.seniority',
    kind: 'select',
    label: 'Seniority',
    hint: 'Only postings whose title states a level are labelled.',
    options: seniorityOptions,
    sourceTypes: jobBoards,
  },
  {
    id: 'posted',
    field: 'metadata.postedAt',
    kind: 'sinceDays',
    label: 'Posted within',
    options: [
      { value: '1', label: 'Last 24 hours' },
      { value: '7', label: 'Last week' },
      { value: '30', label: 'Last month' },
    ],
    sourceTypes: jobBoards,
  },
]

export function filtersFor(sourceType: SourceType | null): SearchFilterSpec[] {
  return searchFilters.filter(
    (spec) => !spec.sourceTypes || (sourceType !== null && spec.sourceTypes.includes(sourceType)),
  )
}

/**
 * A union, not an intersection: a filter that matches only some of the sources still narrows
 * usefully.
 */
export function filtersForSources(sourceTypes: SourceType[]): SearchFilterSpec[] {
  if (sourceTypes.length === 0) return searchFilters.filter((spec) => !spec.sourceTypes)
  return searchFilters.filter(
    (spec) => !spec.sourceTypes || spec.sourceTypes.some((type) => sourceTypes.includes(type)),
  )
}

export function buildFilters(
  specs: SearchFilterSpec[],
  values: Record<string, string>,
  now: Date = new Date(),
): Blob {
  const filters: Blob = {}

  for (const spec of specs) {
    const raw = values[spec.id]
    if (raw === undefined || raw === '') continue

    switch (spec.kind) {
      case 'term':
      case 'select':
        filters[spec.field] = raw
        break
      case 'boolean':
        if (raw === '1') filters[spec.field] = true
        break
      case 'min': {
        const parsed = Number(raw)
        if (Number.isFinite(parsed)) filters[spec.field] = { gte: parsed }
        break
      }
      case 'sinceDays': {
        const days = Number(raw)
        if (Number.isFinite(days) && days > 0) {
          const since = new Date(now.getTime() - days * 24 * 60 * 60 * 1000)
          filters[spec.field] = { gte: since.toISOString() }
        }
        break
      }
    }
  }
  return filters
}
