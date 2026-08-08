import { Activity, History, Sparkles } from 'lucide-react'
import type { CursorInfo } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Card, CardBody } from '@/components/ui/Card'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentCursorStatus, presentIterableId } from '@/config/presentation'
import { useCursors } from '@/hooks/queries'
import { absoluteTime, formatNumber, relativeTime } from '@/lib/utils'

/**
 * Sync progress, in user language.
 *
 * The backend models this as cursors: one per (iterableId, direction), each with a position, a
 * lease and a status. None of those words appear here. Each iterable becomes a card with two
 * plain lines — history and new items — because the question a user actually has is "is it done
 * yet, and is anything stuck?".
 */
export function ActivityTab({ knowledgeId }: { knowledgeId: string }) {
  const { data, isLoading, error, refetch } = useCursors(knowledgeId)

  if (isLoading) return <SkeletonList rows={2} />
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />
  if (!data || data.length === 0) {
    return (
      <EmptyState icon={Activity} title={labels.activity.empty} description={labels.activity.emptyHint} />
    )
  }

  // Group by iterable, so the two directions of the same stream read as one thing.
  const groups = new Map<string, CursorInfo[]>()
  for (const cursor of data) {
    const existing = groups.get(cursor.iterableId)
    if (existing) existing.push(cursor)
    else groups.set(cursor.iterableId, [cursor])
  }

  return (
    <div className="space-y-4">
      <div className="space-y-3">
        {[...groups.entries()].map(([iterableId, cursors]) => (
          <StreamCard key={iterableId} iterableId={iterableId} cursors={cursors} />
        ))}
      </div>

      <Card className="border-dashed">
        <CardBody className="space-y-1.5 text-xs leading-relaxed text-[var(--text-muted)]">
          <p className="font-medium text-[var(--text)]">{labels.activity.legend}</p>
          <p>
            <strong className="font-medium">{labels.activity.older}</strong> works backwards through
            what was already there when you connected. Once it says complete, your history is fully
            imported and it will not run again.
          </p>
          <p>
            <strong className="font-medium">{labels.activity.newer}</strong> picks up anything added
            since. It waits between checks, so "waiting for the next check" is the normal resting
            state.
          </p>
        </CardBody>
      </Card>
    </div>
  )
}

function StreamCard({ iterableId, cursors }: { iterableId: string; cursors: CursorInfo[] }) {
  const backward = cursors.find((cursor) => cursor.direction === 'BACKWARD')
  const forward = cursors.find((cursor) => cursor.direction === 'FORWARD')
  const name = presentIterableId(iterableId, labels.activity.everything)

  return (
    <Card>
      <CardBody className="space-y-3.5">
        <p className="truncate text-sm font-medium text-[var(--text)]" title={iterableId}>
          {name}
        </p>

        <div className="space-y-3">
          {backward && <StreamLine cursor={backward} icon={History} title={labels.activity.older} />}
          {forward && <StreamLine cursor={forward} icon={Sparkles} title={labels.activity.newer} />}
        </div>

        <Technical>
          <TechnicalPanel
            rows={cursors.flatMap((cursor) => [
              [`${cursor.direction} id`, cursor.id],
              [`${cursor.direction} status`, cursor.status],
              [`${cursor.direction} fetched`, formatNumber(cursor.fetched)],
              [`${cursor.direction} retryCount`, String(cursor.retryCount)],
              [`${cursor.direction} lastRunAt`, absoluteTime(cursor.lastRunAt) ?? 'never'],
              [`${cursor.direction} position`, JSON.stringify(cursor.position)],
              [`${cursor.direction} lastError`, cursor.lastError ?? '—'],
            ]) as [string, React.ReactNode][]}
          />
        </Technical>
      </CardBody>
    </Card>
  )
}

function StreamLine({
  cursor,
  icon: Icon,
  title,
}: {
  cursor: CursorInfo
  icon: typeof History
  title: string
}) {
  const presented = presentCursorStatus(cursor.status)
  const lastRun = relativeTime(cursor.lastRunAt)

  const detail =
    cursor.fetched > 0
      ? labels.activity.imported(cursor.fetched)
      : lastRun
        ? labels.activity.lastChecked(lastRun)
        : labels.activity.neverChecked

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
      <Icon className="size-4 shrink-0 text-[var(--text-subtle)]" aria-hidden />
      <span className="text-sm text-[var(--text)]">{title}</span>
      <span className="text-xs text-[var(--text-muted)]">
        {detail}
        {cursor.fetched > 0 && lastRun && ` · ${labels.activity.lastChecked(lastRun)}`}
      </span>
      <StateBadge state={presented} size="sm" className="ml-auto" />
      {cursor.lastError && (
        <p className="w-full text-xs leading-relaxed text-[var(--tone-alert)]">
          {friendlyLastError(cursor.lastError).detail}
        </p>
      )}
    </div>
  )
}
