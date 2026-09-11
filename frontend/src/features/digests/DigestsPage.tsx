import { AlertTriangle, CalendarClock, ChevronRight, Play, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import type { Digest } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { PageHeader } from '@/components/ui/PageHeader'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { formatDigestInterval, formatDigestWindow } from '@/config/constants'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import { useDigestActions, useDigestRuns, useDigests } from '@/hooks/queries'
import { cn, relativeTime } from '@/lib/utils'
import { DigestForm } from './DigestForm'
import { runOutcome } from './RunView'

/**
 * Every digest, one card each.
 *
 * The cards used to carry the latest run's whole item list, because there was nowhere else to put it.
 * With a detail page they carry only the outcome, and the results live one click away where there is
 * room for them and for every earlier run.
 */
export function DigestsPage() {
  const { data, isLoading, error, refetch } = useDigests()
  const { create } = useDigestActions()
  const [adding, setAdding] = useState(false)

  return (
    <>
      <PageHeader
        title={labels.digests.title}
        subtitle={labels.digests.subtitle}
        actions={
          !adding && (
            <Button variant="primary" onClick={() => setAdding(true)}>
              <Plus />
              {labels.digests.add}
            </Button>
          )
        }
      />

      {adding && (
        <div className="mb-5">
          <DigestForm
            pending={create.isPending}
            submitLabel={labels.digests.create}
            onCancel={() => setAdding(false)}
            onSubmit={(body) =>
              create.mutate(body, {
                onSuccess: () => setAdding(false),
                onError: (cause) =>
                  toast.error(labels.digests.createFailed, {
                    description: friendlyError(cause).detail,
                  }),
              })
            }
          />
        </div>
      )}

      {isLoading ? (
        <SkeletonList rows={2} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : !data || data.length === 0 ? (
        !adding && (
          <EmptyState
            icon={CalendarClock}
            title={labels.digests.empty}
            description={labels.digests.emptyHint}
            action={
              <Button variant="primary" onClick={() => setAdding(true)}>
                <Plus />
                {labels.digests.add}
              </Button>
            }
          />
        )
      ) : (
        <div className="space-y-3">
          {data.map((digest) => (
            <DigestCard key={digest.id} digest={digest} />
          ))}
        </div>
      )}
    </>
  )
}

function DigestCard({ digest }: { digest: Digest }) {
  const { setEnabled, run } = useDigestActions()
  const { data: runs } = useDigestRuns(digest.id, 1, 0)
  const latest = runs?.[0]
  const outcome = latest ? runOutcome(latest, digest) : null
  const running = run.isPending && run.variables === digest.id

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start gap-3">
        <Link to={`/digests/${digest.id}`} className="group min-w-0 flex-1">
          <h3 className="flex items-center gap-1 truncate text-sm font-medium group-hover:text-[var(--accent)]">
            {digest.name}
            <ChevronRight className="size-3.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100" aria-hidden />
          </h3>
          <p className="mt-0.5 truncate text-xs text-[var(--text-muted)]">
            {digest.sourceEntityId ? labels.digests.searchesLike : (digest.query ?? '')}
            {digest.interval && ` · ${formatDigestInterval(digest.interval)}`}
            {/* The window labels already read as phrases ("Last week"), so no prefix. */}
            {digest.window && ` · ${formatDigestWindow(digest.window)}`}
          </p>
        </Link>

        <div className="flex items-center gap-2">
          <Toggle
            checked={digest.enabled}
            onCheckedChange={(enabled) => setEnabled.mutate({ id: digest.id, enabled })}
            label={digest.enabled ? labels.digests.pause : labels.digests.resume}
          />
          <Button
            variant="ghost"
            size="sm"
            loading={running}
            onClick={() => run.mutate(digest.id)}
          >
            <Play className="size-3.5" aria-hidden />
            {running ? labels.digests.running : labels.digests.runNow}
          </Button>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-[var(--border)] pt-3 text-xs">
        {!latest ? (
          <span className="text-[var(--text-subtle)]">{labels.digests.neverRun}</span>
        ) : (
          <>
            <span className="text-[var(--text-muted)]">
              {labels.digests.lastRun} {relativeTime(latest.ranAt)}
            </span>
            <span className="text-[var(--text-subtle)]">·</span>
            <span
              className={cn(
                'flex items-center gap-1',
                outcome?.failed ? 'text-[var(--tone-alert)]' : 'text-[var(--text)]',
              )}
            >
              {outcome?.failed && <AlertTriangle className="size-3.5" aria-hidden />}
              {outcome?.text}
            </span>
          </>
        )}
        <Link
          to={`/digests/${digest.id}`}
          className="ml-auto text-[11px] text-[var(--accent)] hover:underline"
        >
          {labels.digests.tabRuns}
        </Link>
      </div>
    </Card>
  )
}
