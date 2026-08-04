import { Pause, Play, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { connectorFor } from '@/config/connectors'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentSource } from '@/config/presentation'
import { useCursors, useKnowledge, useKnowledgeLifecycle } from '@/hooks/queries'
import { cn } from '@/lib/utils'
import { ActivityTab } from './ActivityTab'
import { ItemsTab } from './ItemsTab'
import { OverviewTab } from './OverviewTab'
import { SettingsTab } from './SettingsTab'

const tabs = [
  { id: 'overview', label: labels.detail.overview },
  { id: 'items', label: labels.detail.items },
  { id: 'activity', label: labels.detail.activity },
  { id: 'settings', label: labels.detail.settings },
] as const

type TabId = (typeof tabs)[number]['id']

export function SourceDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [pendingRemoval, setPendingRemoval] = useState(false)

  const { data: knowledge, isLoading, error, refetch, armPolling } = useKnowledge(id)
  // Cursors sharpen the derived state ("importing older items" vs a generic "processing"), and
  // the activity tab needs them anyway — so fetch once here and share.
  const { data: cursors } = useCursors(id)
  const { pause, resume, remove, sync } = useKnowledgeLifecycle(id ?? '')

  const activeTab = (params.get('tab') as TabId | null) ?? 'overview'
  const setTab = (tab: TabId) => {
    const next = new URLSearchParams(params)
    next.set('tab', tab)
    if (tab !== 'items') next.delete('filter')
    setParams(next, { replace: true })
  }

  if (isLoading) return <SkeletonList rows={3} />
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />
  if (!knowledge) return null

  const descriptor = connectorFor(knowledge.connectorDetails.type)
  const presented = presentSource(knowledge, cursors)
  const isPaused = knowledge.status === 'PAUSED'
  const isRemoved = knowledge.status === 'DELETED'

  const act = (
    mutation: {
      mutate: (v: void, o?: { onSuccess?: () => void; onError?: (e: unknown) => void }) => void
    },
    successMessage: string,
  ) =>
    mutation.mutate(undefined, {
      onSuccess: () => {
        toast.success(successMessage)
        armPolling()
      },
      onError: (mutationError: unknown) => {
        const friendly = friendlyError(mutationError)
        toast.error(friendly.title, { description: friendly.detail })
      },
    })

  return (
    <>
      <PageHeader
        title={knowledge.name}
        subtitle={descriptor.label}
        backTo="/knowledge"
        backLabel={labels.sources.title}
        meta={<StateBadge state={presented} />}
        actions={
          !isRemoved && (
            <>
              <Button
                variant="secondary"
                loading={sync.isPending}
                disabled={isPaused}
                onClick={() => act(sync, labels.sources.checking)}
              >
                <RefreshCw />
                {labels.sources.checkNow}
              </Button>
              <Button
                variant="secondary"
                loading={pause.isPending || resume.isPending}
                onClick={() =>
                  isPaused
                    ? act(resume, `Resumed "${knowledge.name}"`)
                    : act(pause, `Paused "${knowledge.name}"`)
                }
              >
                {isPaused ? <Play /> : <Pause />}
                {isPaused ? labels.sources.resume : labels.sources.pause}
              </Button>
              <Button variant="dangerGhost" size="icon" onClick={() => setPendingRemoval(true)}>
                <Trash2 />
                <span className="sr-only">{labels.sources.remove}</span>
              </Button>
            </>
          )
        }
      />

      {knowledge.lastError && (
        <ErrorState
          error={new Error(friendlyLastError(knowledge.lastError).raw ?? knowledge.lastError)}
          className="mb-5"
        />
      )}

      <div
        role="tablist"
        aria-label={knowledge.name}
        className="mb-6 flex gap-1 border-b border-[var(--border)]"
      >
        {tabs.map((tab) => (
          <button
            key={tab.id}
            role="tab"
            aria-selected={activeTab === tab.id}
            onClick={() => setTab(tab.id)}
            className={cn(
              '-mb-px border-b-2 px-3.5 py-2.5 text-sm font-medium transition-colors',
              activeTab === tab.id
                ? 'border-[var(--accent)] text-[var(--text)]'
                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text)]',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'overview' && (
        <OverviewTab knowledge={knowledge} cursors={cursors} onOpenItems={() => setTab('items')} />
      )}
      {activeTab === 'items' && <ItemsTab knowledgeId={knowledge.id} />}
      {activeTab === 'activity' && <ActivityTab knowledgeId={knowledge.id} />}
      {activeTab === 'settings' && <SettingsTab knowledge={knowledge} />}

      <ConfirmDialog
        open={pendingRemoval}
        onOpenChange={setPendingRemoval}
        title={labels.sources.removeTitle(knowledge.name)}
        description={labels.sources.removeBody}
        confirmLabel={labels.sources.remove}
        confirmText={knowledge.name}
        confirmHint={labels.sources.removeConfirmHint(knowledge.name)}
        loading={remove.isPending}
        onConfirm={() =>
          remove.mutate(undefined, {
            onSuccess: () => {
              toast.success(`Removed "${knowledge.name}"`)
              navigate('/knowledge')
            },
            onError: (mutationError: unknown) => {
              const friendly = friendlyError(mutationError)
              toast.error(friendly.title, { description: friendly.detail })
              setPendingRemoval(false)
            },
          })
        }
      />
    </>
  )
}
