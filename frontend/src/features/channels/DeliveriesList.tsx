import { toast } from 'sonner'
import type { Delivery } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { StateBadge } from '@/components/ui/StateBadge'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentDelivery } from '@/config/presentation'
import { useDeliveries, useRetryDelivery } from '@/hooks/queries'
import { absoluteTime, relativeTime } from '@/lib/utils'
import { notifyMutationError } from './notifyTest'

/** A channel's recent deliveries, newest first. Polls while any is still queued. */
export function DeliveriesList({ channelId }: { channelId: string }) {
  const { data, isLoading, error, refetch } = useDeliveries(channelId)
  const retry = useRetryDelivery()

  return (
    <Card>
      <CardHeader>
        <CardTitle>{labels.channels.deliveries}</CardTitle>
      </CardHeader>
      <CardBody className="space-y-3">
        <p className="text-xs leading-relaxed text-[var(--text-muted)]">{labels.channels.deliveriesHint}</p>
        {isLoading ? (
          <SkeletonList rows={2} />
        ) : error ? (
          <ErrorState error={error} compact onRetry={() => void refetch()} />
        ) : !data || data.length === 0 ? (
          <p className="text-sm text-[var(--text-muted)]">{labels.channels.deliveriesEmpty}</p>
        ) : (
          <ul className="divide-y divide-[var(--border)]">
            {data.map((delivery) => (
              <DeliveryRow
                key={delivery.id}
                delivery={delivery}
                retrying={retry.isPending && retry.variables === delivery.id}
                onRetry={() =>
                  retry.mutate(delivery.id, {
                    onSuccess: () => toast.success(labels.channels.retried),
                    onError: notifyMutationError,
                  })
                }
              />
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  )
}

function DeliveryRow({
  delivery,
  retrying,
  onRetry,
}: {
  delivery: Delivery
  retrying: boolean
  onRetry: () => void
}) {
  const sent = delivery.sentAt ? relativeTime(delivery.sentAt) : null
  const queued = relativeTime(delivery.createdAt)
  const itemCount = delivery.message?.items?.length ?? 0

  return (
    <li className="py-3 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <p className="truncate text-sm font-medium text-[var(--text)]">
            {delivery.message?.title || labels.channels.untitled}
          </p>
          <p className="text-xs text-[var(--text-muted)]">
            {[
              itemCount > 0 ? labels.channels.itemCount(itemCount) : null,
              sent ? labels.channels.sent(sent) : queued ? labels.channels.queued(queued) : null,
              delivery.attempts > 0 ? labels.channels.failedAttempts(delivery.attempts) : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
          {delivery.status !== 'SENT' && delivery.lastError && (
            <p className="text-xs leading-relaxed text-[var(--tone-alert)]">
              {friendlyLastError(delivery.lastError).detail}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <StateBadge state={presentDelivery(delivery)} size="sm" />
          {delivery.status === 'FAILED' && (
            <Button variant="secondary" size="sm" onClick={onRetry} loading={retrying}>
              {labels.channels.retry}
            </Button>
          )}
        </div>
      </div>

      <Technical className="mt-2.5">
        <TechnicalPanel
          rows={[
            ['id', delivery.id],
            ['status', delivery.status],
            ['attempts', String(delivery.attempts)],
            ['origin', delivery.origin.refId ? `${delivery.origin.kind} ${delivery.origin.refId}` : delivery.origin.kind],
            ['dedupeKey', delivery.dedupeKey ?? '—'],
            ['providerMessageId', delivery.providerMessageId ?? '—'],
            ['createdAt', absoluteTime(delivery.createdAt)],
            ['sentAt', absoluteTime(delivery.sentAt)],
            ['nextAttemptAt', absoluteTime(delivery.nextAttemptAt)],
            ['leasedUntil', absoluteTime(delivery.leasedUntil)],
            ['lastError', delivery.lastError ?? '—'],
          ]}
        />
      </Technical>
    </li>
  )
}
