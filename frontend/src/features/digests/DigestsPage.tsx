import { AlertTriangle, CalendarClock, Play, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { Digest } from '@/api/types'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import { useDigestActions, useDigestRuns, useDigests } from '@/hooks/queries'
import { relativeTime } from '@/lib/utils'
import { NewDigestForm } from './NewDigestForm'

export function DigestsPage() {
  const { data, isLoading, error, refetch } = useDigests()
  const { create, setEnabled, remove, run } = useDigestActions()
  const [adding, setAdding] = useState(false)
  const [pendingRemoval, setPendingRemoval] = useState<Digest | null>(null)

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
          <NewDigestForm
            pending={create.isPending}
            onCancel={() => setAdding(false)}
            onCreate={(body) =>
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
            <DigestCard
              key={digest.id}
              digest={digest}
              running={run.isPending && run.variables === digest.id}
              onRun={() => run.mutate(digest.id)}
              onToggle={(enabled) => setEnabled.mutate({ id: digest.id, enabled })}
              onRemove={() => setPendingRemoval(digest)}
            />
          ))}
        </div>
      )}

      <ConfirmDialog
        open={pendingRemoval !== null}
        onOpenChange={(open) => !open && setPendingRemoval(null)}
        title={labels.digests.removeConfirm}
        description={labels.digests.removeBody}
        confirmLabel={labels.digests.remove}
        loading={remove.isPending}
        onConfirm={() => {
          if (pendingRemoval) remove.mutate(pendingRemoval.id)
          setPendingRemoval(null)
        }}
      />
    </>
  )
}

interface CardProps {
  digest: Digest
  running: boolean
  onRun: () => void
  onToggle: (enabled: boolean) => void
  onRemove: () => void
}

function DigestCard({ digest, running, onRun, onToggle, onRemove }: CardProps) {
  const { data: runs } = useDigestRuns(digest.id)
  const latest = runs?.[0]

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start gap-3">
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-sm font-medium">{digest.name}</h3>
          <p className="mt-0.5 truncate text-xs text-[var(--text-muted)]">
            {digest.sourceEntityId
              ? `Like ${digest.sourceEntityId}`
              : (digest.query ?? '')}
            {digest.interval && ` · every ${digest.interval}`}
            {digest.window && ` · looking back ${digest.window}`}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Toggle
            checked={digest.enabled}
            onCheckedChange={onToggle}
            label={digest.enabled ? labels.digests.pause : labels.digests.resume}
          />
          <Button variant="ghost" size="sm" onClick={onRun} loading={running}>
            <Play className="size-3.5" aria-hidden />
            {running ? labels.digests.running : labels.digests.runNow}
          </Button>
          <Button variant="ghost" size="sm" onClick={onRemove} aria-label={labels.digests.remove}>
            <Trash2 className="size-3.5" aria-hidden />
          </Button>
        </div>
      </div>

      <div className="mt-3 border-t border-[var(--border)] pt-3">
        {!latest ? (
          <p className="text-xs text-[var(--text-subtle)]">{labels.digests.neverRun}</p>
        ) : latest.error ? (
          // A failed run is shown, not hidden: a digest that has been erroring for a week should
          // look broken rather than merely quiet.
          <div className="flex gap-2">
            <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-[var(--tone-alert)]" aria-hidden />
            <div className="min-w-0">
              <p className="text-xs text-[var(--text-muted)]">{labels.digests.runFailed}</p>
              <p className="truncate text-[11px] text-[var(--text-subtle)]">{latest.error}</p>
            </div>
          </div>
        ) : (
          <>
            <p className="mb-2 text-xs text-[var(--text-muted)]">
              {labels.digests.lastRun} {relativeTime(latest.ranAt)} ·{' '}
              {latest.items.length === 0
                ? labels.digests.noResults
                : labels.digests.resultCount(latest.items.length)}
            </p>

            {latest.taskOutput && (
              <pre className="mb-2 max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-[var(--surface-sunken)] p-2.5 text-[11px] leading-relaxed text-[var(--text-muted)]">
                {latest.taskOutput}
              </pre>
            )}

            <ul className="space-y-1">
              {latest.items.map((item) => (
                <li key={item.chunkId} className="truncate text-xs">
                  {item.uri ? (
                    <a
                      href={item.uri}
                      target="_blank"
                      rel="noreferrer"
                      className="text-[var(--accent)] hover:underline"
                    >
                      {item.title || item.uri}
                    </a>
                  ) : (
                    <span>{item.title || item.entityId}</span>
                  )}
                  <Technical>
                    <span className="ml-2 text-[10px] text-[var(--text-subtle)]">
                      {item.entityId} · {item.score.toFixed(3)}
                    </span>
                  </Technical>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </Card>
  )
}
