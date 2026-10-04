import type {
  EntityFilterValue,
  EntityQueryBody,
  EntityType,
  Knowledge,
  SourceType,
  Task,
  TaskFieldType,
} from '@/api/types'
import { companyFor } from './companies'
import { labels } from './labels'
import { seniorityOptions } from './searchFilters'

/**
 * The standalone `/jobs` page. Everything job-specific about it lives here; the endpoints it reads
 * are the generic `/api/entities` ones.
 *
 * `multi` is any-of over `options`, or over the field's facet values when none are given; `range`
 * reads `<id>_min` / `<id>_max`; `sinceDays` and `tri` take one of `options`.
 */
export type JobFilterKind = 'search' | 'multi' | 'contains' | 'toggle' | 'sinceDays' | 'range' | 'tri'

export interface JobFilterSpec {
  id: string
  label: string
  kind: JobFilterKind
  path?: string
  options?: { value: string; label: string }[]
  placeholder?: string
  /** Facet values sharing a label become one chip that selects all of them. */
  labelFor?: (value: string) => string
}

export type EnrichedDisplay =
  | { as: 'fact'; format?: (value: unknown) => string }
  | { as: 'tags' }
  | { as: 'description' }
  | { as: 'hidden' }

const sinceOptions = [
  { value: '1', label: '24 hours' },
  { value: '3', label: '3 days' },
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
]

const triOptions = [
  { value: 'yes', label: 'Yes' },
  { value: 'no', label: 'No' },
]

export const jobDashboard = {
  entityType: 'JOB_POSTING' as EntityType,
  sourceType: 'JOB_BOARDS' as SourceType,
  pageSize: 25,
  /** The `custom` keys the row controls write. */
  marks: { status: 'status', statusAt: 'statusAt', hidden: 'hidden' },
  /**
   * Where a known metadata-task field goes on a card. A field not listed is placed by its value: a
   * list becomes tags, long text a description, anything else a `name value` fact.
   */
  enrichedDisplay: {
    minYoe: { as: 'fact', format: (value: unknown) => labels.jobs.yoe(String(value)) },
    skills: { as: 'tags' },
    // The card's location comes from the board; the task's list repeats it with Remote/Hybrid mixed in.
    locations: { as: 'hidden' },
  } as Record<string, EnrichedDisplay>,
  tagLimit: 6,
  /** Browser-only conveniences; nothing here is shared or sent to the backend. */
  storage: { lastVisit: 'jobs.lastVisit', since: 'jobs.since', mySkills: 'jobs.mySkills' },
  /** One per posting, in `custom.status`; `tone` names a `--tone-*` CSS variable pair. */
  statuses: [
    { value: 'INTERESTED', label: 'Interested', tone: 'alert' },
    { value: 'REACHED_OUT', label: 'Reached out', tone: 'busy' },
    { value: 'APPLIED', label: 'Applied', tone: 'ok' },
    { value: 'APPLIED_COLD', label: 'Applied – cold', tone: 'wait' },
  ],
  filters: [
    { id: 'q', label: 'Title', kind: 'search', placeholder: 'Search titles' },
    {
      id: 'company',
      label: 'Company',
      kind: 'multi',
      path: 'metadata.company',
      labelFor: (value: string) => companyFor(value)?.label ?? value,
    },
    { id: 'location', label: 'Location', kind: 'contains', path: 'metadata.location', placeholder: 'e.g. Bengaluru' },
    { id: 'remote', label: 'Remote only', kind: 'toggle', path: 'metadata.remote' },
    { id: 'seniority', label: 'Seniority', kind: 'multi', path: 'metadata.seniority', options: seniorityOptions },
    { id: 'team', label: 'Team', kind: 'multi', path: 'metadata.team' },
    { id: 'posted', label: 'Posted within', kind: 'sinceDays', path: 'metadata.postedAt', options: sinceOptions },
    { id: 'seen', label: 'First seen within', kind: 'sinceDays', path: 'createdAt', options: sinceOptions },
    { id: 'platform', label: 'Platform', kind: 'multi', path: 'metadata.platform' },
  ] satisfies JobFilterSpec[] as JobFilterSpec[],
}

/** Knowledges the page lists postings from. */
export function jobKnowledges(all: Knowledge[] | undefined): Knowledge[] {
  return (all ?? []).filter(
    (k) => k.connectorDetails.type === jobDashboard.sourceType && k.status !== 'DELETED',
  )
}

const enrichedKinds: Record<TaskFieldType, JobFilterKind> = {
  NUMBER: 'range',
  BOOLEAN: 'tri',
  TEXT: 'multi',
  LIST: 'multi',
}

/**
 * One filter per field of the knowledges' metadata tasks, so a field added to the task becomes a
 * filter with no change here. Labels are the field names.
 */
export function enrichedFilters(knowledges: Knowledge[], tasks: Task[] | undefined): JobFilterSpec[] {
  const taskIds = new Set(knowledges.map((k) => k.config.enrichment?.taskId).filter(Boolean))
  const seen = new Set<string>()
  const out: JobFilterSpec[] = []
  for (const task of tasks ?? []) {
    if (!taskIds.has(task.id)) continue
    for (const field of task.fields ?? []) {
      if (seen.has(field.name)) continue
      seen.add(field.name)
      out.push({
        id: `e_${field.name}`,
        label: field.name,
        kind: enrichedKinds[field.type] ?? 'multi',
        path: `enriched.${field.name}`,
        options:
          field.type === 'BOOLEAN'
            ? triOptions
            : field.values?.length
              ? field.values.map((v) => ({ value: v, label: v }))
              : undefined,
      })
    }
  }
  return out
}

/** Paths whose options come from facet counts rather than a fixed list. */
export function facetPaths(specs: JobFilterSpec[]): string[] {
  return specs.filter((s) => s.kind === 'multi' && !s.options && s.path).map((s) => s.path!)
}

export type JobParams = Record<string, string>

/** The status filter's token for postings with no status yet. */
export const NO_STATUS = 'NONE'

export function statusFor(value: unknown) {
  return jobDashboard.statuses.find((s) => s.value === value)
}

const DAY_MS = 86_400_000

function isNumber(value: string | undefined): value is string {
  return value !== undefined && value !== '' && !Number.isNaN(Number(value))
}

/** Builds the query from URL params. Hidden postings are left out unless `showHidden` is set. */
export function buildJobQuery(
  specs: JobFilterSpec[],
  params: JobParams,
  knowledgeIds: string[],
  offset: number,
): EntityQueryBody {
  const filters: Record<string, EntityFilterValue> = {}
  const isOps = (v: EntityFilterValue | undefined) => typeof v === 'object' && v !== null && !Array.isArray(v)
  // Two operator objects on one path (a min and a max) merge into one; anything else replaces.
  const add = (path: string, value: EntityFilterValue) => {
    const current = filters[path]
    filters[path] = isOps(current) && isOps(value) ? { ...(current as object), ...(value as object) } : value
  }

  for (const spec of specs) {
    const raw = params[spec.id]
    if (!spec.path) continue
    switch (spec.kind) {
      case 'multi':
        if (raw) add(spec.path, raw.split(','))
        break
      case 'contains':
        if (raw) add(spec.path, { contains: raw })
        break
      case 'toggle':
        if (raw === '1') add(spec.path, true)
        break
      case 'tri':
        if (raw === 'yes' || raw === 'no') add(spec.path, raw === 'yes')
        break
      case 'sinceDays':
        if (raw && Number(raw) > 0) {
          add(spec.path, { gte: new Date(Date.now() - Number(raw) * DAY_MS).toISOString() })
        }
        break
      case 'range': {
        const min = params[`${spec.id}_min`]
        const max = params[`${spec.id}_max`]
        if (isNumber(min)) add(spec.path, { gte: Number(min) })
        if (isNumber(max)) add(spec.path, { lte: Number(max) })
        break
      }
    }
  }

  const statuses = params.status ? params.status.split(',') : []
  if (statuses.length > 0) {
    const chosen = statuses.filter((s) => s !== NO_STATUS)
    const all = jobDashboard.statuses.map((s) => s.value)
    // $nin also matches a missing field, which is how "no status" joins the chosen ones.
    add(
      `custom.${jobDashboard.marks.status}`,
      statuses.includes(NO_STATUS) ? { nin: all.filter((v) => !chosen.includes(v)) } : chosen,
    )
  }
  if (params.showHidden !== '1') {
    add(`custom.${jobDashboard.marks.hidden}`, { ne: true })
  }

  const selectedSources = params.source ? params.source.split(',') : []
  return {
    entityTypes: [jobDashboard.entityType],
    knowledgeIds: selectedSources.length > 0 ? selectedSources : knowledgeIds,
    q: params.q || null,
    filters,
    limit: jobDashboard.pageSize,
    offset,
  }
}
