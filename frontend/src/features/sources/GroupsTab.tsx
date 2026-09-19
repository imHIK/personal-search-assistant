import { AlertTriangle, ArrowRight, History, Layers } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { ConnectorDescriptor } from '@/config/connectors'
import type { CursorInfo } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Card, CardBody } from '@/components/ui/Card'
import { FilterBar } from '@/components/ui/FilterBar'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Tooltip } from '@/components/ui/Tooltip'
import { friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { groupFilters, selectedValues, type ListFilterValues } from '@/config/listFilters'
import {
  groupAlert,
  groupName,
  groupState,
  isImportingHistory,
  presentGroup,
} from '@/config/presentation'
import { useCursors } from '@/hooks/queries'
import { absoluteTime, formatNumber, relativeTime } from '@/lib/utils'

/**
 * Sync progress, one row per group.
 *
 * The backend models this as cursors: one per (iterableId, direction), each with a position, a
 * lease and a status. None of those words appear here, and neither does the direction — a card per
 * group with a line per direction meant a forward-only source (every job board) repeated "New
 * items" on every row while the real question, "is this one moving, stuck or done", was split
 * across two badges. One row, one state; history only gets named while it is actually being walked.
 */
export function GroupsTab({
  knowledgeId,
  descriptor,
}: {
  knowledgeId: string
  descriptor: ConnectorDescriptor
}) {
  const { data, isLoading, error, refetch } = useCursors(knowledgeId)
  const [params, setParams] = useSearchParams()

  const values: ListFilterValues = useMemo(() => {
    const next: ListFilterValues = {}
    for (const spec of groupFilters) {
      const value = params.get(spec.id)
      if (value) next[spec.id] = value
    }
    return next
  }, [params])

  const setValue = (id: string, value: string) => {
    const next = new URLSearchParams(params)
    next.set('tab', 'groups')
    if (value) next.set(id, value)
    else next.delete(id)
    setParams(next, { replace: true })
  }

  const clearFilters = () => {
    const next = new URLSearchParams(params)
    for (const spec of groupFilters) next.delete(spec.id)
    setParams(next, { replace: true })
  }

  // Grouped by iterable, so the two directions of the same stream read as one thing.
  const groups = useMemo(() => {
    const byIterable = new Map<string, CursorInfo[]>()
    for (const cursor of data ?? []) {
      const existing = byIterable.get(cursor.iterableId)
      if (existing) existing.push(cursor)
      else byIterable.set(cursor.iterableId, [cursor])
    }
    return [...byIterable.entries()].map(([iterableId, cursors]) => ({
      iterableId,
      cursors,
      name: groupName(iterableId, cursors, labels.groups.everything),
    }))
  }, [data])

  // Both filters are applied here rather than by the API: cursors arrive as one unpaged array, so
  // there is nothing to page and no request to save.
  const needle = (values.gq ?? '').trim().toLowerCase()
  const wantedStates = selectedValues(values, 'gstate')
  const visible = groups.filter((group) => {
    if (needle && !group.name.toLowerCase().includes(needle)
      && !group.iterableId.toLowerCase().includes(needle)) {
      return false
    }
    return wantedStates.length === 0 || wantedStates.includes(groupState(group.cursors))
  })

  if (isLoading) return <SkeletonList rows={3} />
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />
  if (groups.length === 0) {
    return (
      <EmptyState icon={Layers} title={labels.groups.empty} description={labels.groups.emptyHint} />
    )
  }

  return (
    <div className="space-y-4">
      <FilterBar
        specs={groupFilters}
        values={values}
        onChange={setValue}
        onClear={clearFilters}
      />

      {visible.length === 0 ? (
        <EmptyState icon={Layers} title={labels.groups.emptyFiltered} />
      ) : (
        <Card className="divide-y divide-[var(--border)]">
          {visible.map((group) => (
            <GroupRow
              key={group.iterableId}
              iterableId={group.iterableId}
              name={group.name}
              cursors={group.cursors}
            />
          ))}
        </Card>
      )}

      <Card className="border-dashed">
        <CardBody className="space-y-1.5 text-xs leading-relaxed text-[var(--text-muted)]">
          <p className="font-medium text-[var(--text)]">
            {labels.groups.legend}
            {descriptor.groupNoun && ` · ${descriptor.groupNoun.many.toLowerCase()}`}
          </p>
          <p>
            <strong className="font-medium">Synced</strong> {labels.groups.legendSynced}
          </p>
          <p>
            <strong className="font-medium">Syncing</strong> {labels.groups.legendSyncing}
          </p>
          <p>
            <strong className="font-medium">Failed</strong> {labels.groups.legendFailed}
          </p>
        </CardBody>
      </Card>
    </div>
  )
}

function GroupRow({
  iterableId,
  name,
  cursors,
}: {
  iterableId: string
  name: string
  cursors: CursorInfo[]
}) {
  const presented = presentGroup(cursors)
  // Lands on Items narrowed to this group; the group-tab filters are dropped since they mean nothing there.
  const itemsLink = { search: `?${new URLSearchParams({ tab: 'items', group: iterableId })}` }
  const alert = groupAlert(cursors)
  const fetched = cursors.reduce((total, cursor) => total + cursor.fetched, 0)
  const lastRun = cursors
    .map((cursor) => cursor.lastRunAt)
    .filter((value): value is string => Boolean(value))
    .sort()
    .at(-1)
  const checked = relativeTime(lastRun ?? null)

  const detail = [
    fetched > 0 ? labels.groups.imported(fetched) : null,
    checked ? labels.groups.lastChecked(checked) : labels.groups.neverChecked,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="px-4 py-2.5">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Link
          to={itemsLink}
          className="min-w-0 flex-1 truncate text-sm font-medium text-[var(--text)] hover:text-[var(--accent)]"
          title={iterableId}
        >
          {name}
        </Link>

        {isImportingHistory(cursors) && (
          <Tooltip content={labels.groups.importingHistoryHint}>
            <span className="inline-flex items-center gap-1 rounded-full bg-[var(--surface-sunken)] px-2 py-0.5 text-[11px] text-[var(--text-muted)]">
              <History className="size-3" aria-hidden />
              {labels.groups.importingHistory}
            </span>
          </Tooltip>
        )}

        <span className="text-xs text-[var(--text-muted)]">{detail}</span>

        {alert && (
          <Tooltip content={friendlyLastError(alert).detail}>
            <span className="text-[var(--tone-alert)]" aria-label={friendlyLastError(alert).title}>
              <AlertTriangle className="size-4" aria-hidden />
            </span>
          </Tooltip>
        )}

        <StateBadge state={presented} size="sm" />

        <Link
          to={itemsLink}
          className="inline-flex items-center gap-1 text-xs text-[var(--text-muted)] hover:text-[var(--accent)]"
        >
          {labels.groups.viewItems}
          <ArrowRight className="size-3" aria-hidden />
        </Link>
      </div>

      <Technical>
        <TechnicalPanel
          rows={[
            ['iterableId', iterableId],
            ...cursors.flatMap((cursor) => [
              [`${cursor.direction} id`, cursor.id],
              [`${cursor.direction} status`, cursor.status],
              [`${cursor.direction} fetched`, formatNumber(cursor.fetched)],
              [`${cursor.direction} retryCount`, String(cursor.retryCount)],
              [`${cursor.direction} lastRunAt`, absoluteTime(cursor.lastRunAt) ?? 'never'],
              [`${cursor.direction} nextAttemptAt`, absoluteTime(cursor.nextAttemptAt) ?? '—'],
              [`${cursor.direction} position`, JSON.stringify(cursor.position)],
              [`${cursor.direction} lastError`, cursor.lastError ?? '—'],
            ]),
          ] as [string, React.ReactNode][]}
        />
      </Technical>
    </div>
  )
}
