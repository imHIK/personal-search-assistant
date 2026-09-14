import type { Blob, SourceType } from '@/api/types'

/**
 * One descriptor per offered search filter. This is the search page's mirror of
 * `connectors.ts`: the panel, its controls and the request body are all derived from this list,
 * so no component ever branches on a field name or a SourceType.
 *
 * A spec's `kind` is what turns a control's value into a filter value. The backend reads a scalar
 * as an exact term and a `{ gte, lte }` map as a range, which is what lets "posted this week" and
 * "pays at least" exist at all — they are not expressible as terms.
 *
 * To offer a new filter: append an object. To offer it only for certain sources, list them in
 * `sourceTypes`; a spec with none is offered everywhere.
 */
export type SearchFilterKind = 'term' | 'select' | 'boolean' | 'min' | 'sinceDays'

export interface SearchFilterSpec {
  /** URL parameter name. Also the form control's key. */
  id: string
  /** Full index field path sent to the API. */
  field: string
  kind: SearchFilterKind
  label: string
  hint?: string
  placeholder?: string
  /** `select` and `sinceDays` only. */
  options?: { value: string; label: string }[]
  /**
   * Restrict the filter to sources where it can actually match. A posting facet offered while
   * searching Drive would only ever return nothing, which reads as a broken search rather than an
   * inapplicable filter. Omit to offer the filter everywhere.
   */
  sourceTypes?: SourceType[]
}

const jobBoards: SourceType[] = ['JOB_BOARDS']

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
    options: [
      { value: 'INTERN', label: 'Intern' },
      { value: 'JUNIOR', label: 'Junior' },
      { value: 'SENIOR', label: 'Senior' },
      { value: 'STAFF', label: 'Staff' },
      { value: 'PRINCIPAL', label: 'Principal' },
      { value: 'LEAD', label: 'Lead' },
      { value: 'LEADERSHIP', label: 'Leadership' },
    ],
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
  {
    id: 'pay',
    field: 'metadata.compMin',
    kind: 'min',
    label: 'Pays at least',
    hint: 'Only postings that state a pay range can match.',
    placeholder: '150000',
    sourceTypes: jobBoards,
  },
]

/** The filters worth offering for the current scope. */
export function filtersFor(sourceType: SourceType | null): SearchFilterSpec[] {
  return searchFilters.filter(
    (spec) => !spec.sourceTypes || (sourceType !== null && spec.sourceTypes.includes(sourceType)),
  )
}

/**
 * The filters worth offering across several sources at once — a digest may span more than one.
 * A union rather than an intersection: a filter that matches only some of the selected sources still
 * narrows usefully, whereas hiding it would leave the user unable to express what they want at all.
 */
export function filtersForSources(sourceTypes: SourceType[]): SearchFilterSpec[] {
  if (sourceTypes.length === 0) return searchFilters.filter((spec) => !spec.sourceTypes)
  return searchFilters.filter(
    (spec) => !spec.sourceTypes || spec.sourceTypes.some((type) => sourceTypes.includes(type)),
  )
}

/**
 * Turn the raw control values into the API's `filters` map, skipping anything blank or unusable.
 * A number that will not parse is dropped rather than sent: the backend would read it as a term
 * and silently match nothing, which looks like "the filter broke my search".
 */
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
