import { Library, Pause, Play, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import type { Knowledge } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, ProgressBar, SkeletonList } from '@/components/ui/States'
import { connectorFor } from '@/config/connectors'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentSource, sourceState } from '@/config/presentation'
import { useKnowledgeLifecycle, useKnowledgeList } from '@/hooks/queries'
import { absoluteTime, formatNumber, relativeTime } from '@/lib/utils'

export function SourcesPage() {
  const navigate = useNavigate()
  const { data, isLoading, error, refetch, armPolling } = useKnowledgeList()
  const [pendingRemoval, setPendingRemoval] = useState<Knowledge | null>(null)

  const sources = (data ?? []).filter((knowledge) => knowledge.status !== 'DELETED')

  return (
    <>
      <PageHeader
        title={labels.sources.title}
        subtitle={labels.sources.subtitle}
        actions={
          <Button variant="primary" onClick={() => navigate('/knowledge/new')}>
            <Plus />
            {labels.sources.add}
          </Button>
        }
      />

      {isLoading ? (
        <SkeletonList rows={3} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : sources.length === 0 ? (
        <EmptyState
          icon={Library}
          title={labels.sources.empty}
          description={labels.sources.emptyHint}
          action={
            <Button variant="primary" onClick={() => navigate('/knowledge/new')}>
              <Plus />
              {labels.sources.add}
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {sources.map((knowledge) => (
            <SourceCard
              key={knowledge.id}
              knowledge={knowledge}
              onRemove={() => setPendingRemoval(knowledge)}
              onMutated={armPolling}
            />
          ))}
        </div>
      )}

      <RemoveDialog
        knowledge={pendingRemoval}
        onClose={() => setPendingRemoval(null)}
        onRemoved={armPolling}
      />
    </>
  )
}

function SourceCard({
  knowledge,
  onRemove,
  onMutated,
}: {
  knowledge: Knowledge
  onRemove: () => void
  onMutated: () => void
}) {
  const descriptor = connectorFor(knowledge.connectorDetails.type)
  const Icon = descriptor.icon
  const state = sourceState(knowledge)
  const presented = presentSource(knowledge)
  const { pause, resume, sync } = useKnowledgeLifecycle(knowledge.id)

  const { entities, indexed, failed } = knowledge.stats
  const busy = state === 'processing' || state === 'importing' || state === 'setting-up'
  const isPaused = knowledge.status === 'PAUSED'

  const act = (
    mutation: { mutate: (v: void, o?: { onSuccess?: () => void; onError?: (e: unknown) => void }) => void },
    successMessage: string,
  ) => {
    mutation.mutate(undefined, {
      onSuccess: () => {
        toast.success(successMessage)
        onMutated()
      },
      onError: (mutationError: unknown) => {
        const friendly = friendlyError(mutationError)
        toast.error(friendly.title, { description: friendly.detail })
      },
    })
  }

  return (
    <Card className="px-5 py-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 gap-3.5">
          <div className="mt-0.5 rounded-lg bg-[var(--bg-subtle)] p-2">
            <Icon className="size-4 text-[var(--text-muted)]" aria-hidden />
          </div>
          <div className="min-w-0 space-y-1">
            <Link
              to={`/knowledge/${knowledge.id}`}
              className="block truncate text-sm font-medium text-[var(--text)] hover:text-[var(--accent)]"
            >
              {knowledge.name}
            </Link>
            <p className="text-xs text-[var(--text-muted)]">
              {descriptor.label}
              {presented.hint && ` · ${presented.hint}`}
            </p>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          <StateBadge state={presented} size="sm" className="mr-1" />
          <Button
            variant="ghost"
            size="iconSm"
            title={labels.sources.checkNow}
            aria-label={`${labels.sources.checkNow} — ${knowledge.name}`}
            loading={sync.isPending}
            disabled={isPaused}
            onClick={() => act(sync, labels.sources.checking)}
          >
            <RefreshCw />
          </Button>
          <Button
            variant="ghost"
            size="iconSm"
            title={isPaused ? labels.sources.resume : labels.sources.pause}
            aria-label={`${isPaused ? labels.sources.resume : labels.sources.pause} — ${knowledge.name}`}
            loading={pause.isPending || resume.isPending}
            onClick={() =>
              isPaused
                ? act(resume, `Resumed "${knowledge.name}"`)
                : act(pause, `Paused "${knowledge.name}"`)
            }
          >
            {isPaused ? <Play /> : <Pause />}
          </Button>
          <Button
            variant="dangerGhost"
            size="iconSm"
            title={labels.sources.remove}
            aria-label={`${labels.sources.remove} — ${knowledge.name}`}
            onClick={onRemove}
          >
            <Trash2 />
          </Button>
        </div>
      </div>

      {knowledge.lastError && (
        <p className="mt-3 rounded-lg bg-[var(--tone-alert-bg)] px-3 py-2 text-xs leading-relaxed text-[var(--tone-alert)]">
          {friendlyLastError(knowledge.lastError).detail}
        </p>
      )}

      <div className="mt-3.5 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-[var(--text-muted)]">
        <span>{labels.sources.itemsSearchable(indexed)}</span>
        {entities - indexed - failed > 0 && (
          <span className="text-[var(--tone-busy)]">
            {labels.sources.itemsProcessing(entities - indexed - failed)}
          </span>
        )}
        {failed > 0 && (
          <Link
            to={`/knowledge/${knowledge.id}?tab=items&filter=failed`}
            className="text-[var(--tone-alert)] underline-offset-2 hover:underline"
          >
            {labels.sources.itemsFailed(failed)}
          </Link>
        )}
        <span className="ml-auto">
          {labels.sources.lastChecked} {relativeTime(knowledge.updatedAt) ?? labels.sources.never}
        </span>
      </div>

      {busy && entities > 0 && (
        <ProgressBar
          value={indexed}
          max={entities}
          tone="busy"
          className="mt-3"
          label={labels.detail.progress(indexed, entities)}
        />
      )}

      <Technical className="mt-3.5">
        <TechnicalPanel
          rows={[
            ['id', knowledge.id],
            ['status', knowledge.status],
            ['stats', `entities=${entities} indexed=${indexed} failed=${failed}`],
            ['anchor', absoluteTime(knowledge.anchor)],
            ['nextSyncDueAt', absoluteTime(knowledge.nextSyncDueAt) ?? 'null (due now)'],
            ['syncGeneration', formatNumber(knowledge.syncGeneration)],
            ['connectionId', knowledge.connectorDetails.connectionId ?? '— (type default)'],
            ['inputs', JSON.stringify(knowledge.inputs)],
          ]}
        />
      </Technical>
    </Card>
  )
}

/** Extracted so the confirm dialog can own a lifecycle hook bound to the pending source. */
function RemoveDialog({
  knowledge,
  onClose,
  onRemoved,
}: {
  knowledge: Knowledge | null
  onClose: () => void
  onRemoved: () => void
}) {
  const { remove } = useKnowledgeLifecycle(knowledge?.id ?? '')

  return (
    <ConfirmDialog
      open={knowledge !== null}
      onOpenChange={(open) => !open && onClose()}
      title={knowledge ? labels.sources.removeTitle(knowledge.name) : ''}
      description={labels.sources.removeBody}
      confirmLabel={labels.sources.remove}
      confirmText={knowledge?.name}
      confirmHint={knowledge ? labels.sources.removeConfirmHint(knowledge.name) : undefined}
      loading={remove.isPending}
      onConfirm={() => {
        if (!knowledge) return
        remove.mutate(undefined, {
          onSuccess: () => {
            toast.success(`Removed "${knowledge.name}"`)
            onRemoved()
            onClose()
          },
          onError: (error: unknown) => {
            const friendly = friendlyError(error)
            toast.error(friendly.title, { description: friendly.detail })
            onClose()
          },
        })
      }}
    />
  )
}
