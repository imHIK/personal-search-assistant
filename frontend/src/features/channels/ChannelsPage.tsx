import { Plus, Send, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import type { Channel } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { channelFor } from '@/config/channels'
import { friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentChannel } from '@/config/presentation'
import { useChannelMutations, useChannels } from '@/hooks/queries'
import { absoluteTime } from '@/lib/utils'
import { notifyMutationError, notifyTestResult } from './notifyTest'

export function ChannelsPage() {
  const navigate = useNavigate()
  const { data, isLoading, error, refetch } = useChannels()
  const { test, remove } = useChannelMutations()
  const [pendingRemoval, setPendingRemoval] = useState<Channel | null>(null)

  const addButton = (
    <Button variant="primary" onClick={() => navigate('/channels/new')}>
      <Plus />
      {labels.channels.add}
    </Button>
  )

  return (
    <>
      <PageHeader title={labels.channels.title} subtitle={labels.channels.subtitle} actions={addButton} />

      {isLoading ? (
        <SkeletonList rows={2} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : !data || data.length === 0 ? (
        <EmptyState
          icon={Send}
          title={labels.channels.empty}
          description={labels.channels.emptyHint}
          action={addButton}
        />
      ) : (
        <div className="space-y-3">
          {data.map((channel) => (
            <ChannelRow
              key={channel.id}
              channel={channel}
              testing={test.isPending && test.variables === channel.id}
              onTest={() =>
                test.mutate(channel.id, { onSuccess: notifyTestResult, onError: notifyMutationError })
              }
              onRemove={() => setPendingRemoval(channel)}
            />
          ))}
        </div>
      )}

      <ConfirmDialog
        open={pendingRemoval !== null}
        onOpenChange={(open) => !open && setPendingRemoval(null)}
        title={pendingRemoval ? labels.channels.removeTitle(pendingRemoval.name) : ''}
        description={labels.channels.removeBody}
        confirmLabel={labels.channels.remove}
        loading={remove.isPending}
        onConfirm={() => {
          if (!pendingRemoval) return
          remove.mutate(pendingRemoval.id, {
            onSuccess: () => {
              toast.success(labels.channels.removed(pendingRemoval.name))
              setPendingRemoval(null)
            },
            onError: (mutationError) => {
              notifyMutationError(mutationError)
              setPendingRemoval(null)
            },
          })
        }}
      />
    </>
  )
}

function ChannelRow({
  channel,
  testing,
  onTest,
  onRemove,
}: {
  channel: Channel
  testing: boolean
  onTest: () => void
  onRemove: () => void
}) {
  const descriptor = channelFor(channel.type)
  const Icon = descriptor.icon
  const destination = descriptor.summary?.(channel.target ?? {})

  return (
    <Card className="px-5 py-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 gap-3.5">
          <div className="mt-0.5 rounded-lg bg-[var(--bg-subtle)] p-2">
            <Icon className="size-4 text-[var(--text-muted)]" aria-hidden />
          </div>
          <div className="min-w-0 space-y-1">
            <Link
              to={`/channels/${channel.id}`}
              className="block truncate text-sm font-medium text-[var(--text)] hover:text-[var(--accent)]"
            >
              {channel.name}
            </Link>
            <p className="truncate text-xs text-[var(--text-muted)]">
              {descriptor.label}
              {destination && ` · ${destination}`}
            </p>
            {channel.status === 'ERROR' && channel.lastError && (
              <p className="pt-0.5 text-xs leading-relaxed text-[var(--tone-alert)]">
                {friendlyLastError(channel.lastError).detail}
              </p>
            )}
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <StateBadge state={presentChannel(channel)} size="sm" />
          <Button
            variant="ghost"
            size="sm"
            onClick={onTest}
            loading={testing}
            title={labels.channels.testHint}
          >
            {testing ? labels.channels.testing : labels.channels.test}
          </Button>
          <Button asChild variant="secondary" size="sm">
            <Link to={`/channels/${channel.id}`}>{labels.channels.edit}</Link>
          </Button>
          <Button
            variant="dangerGhost"
            size="iconSm"
            onClick={onRemove}
            aria-label={`${labels.channels.remove} ${channel.name}`}
          >
            <Trash2 />
          </Button>
        </div>
      </div>

      <Technical className="mt-3.5">
        <TechnicalPanel
          rows={[
            ['id', channel.id],
            ['type', channel.type],
            ['status', channel.status],
            ['enabled', String(channel.enabled)],
            ['target keys', Object.keys(channel.target ?? {}).join(', ') || '—'],
            ['createdAt', absoluteTime(channel.createdAt)],
            ['updatedAt', absoluteTime(channel.updatedAt)],
            ['lastError', channel.lastError ?? '—'],
          ]}
        />
      </Technical>
    </Card>
  )
}
