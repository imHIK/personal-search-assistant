import { Send } from 'lucide-react'
import { StateBadge } from '@/components/ui/StateBadge'
import { labels } from '@/config/labels'
import { presentDelivery } from '@/config/presentation'
import { useChannels, useRunDeliveries } from '@/hooks/queries'

/**
 * Where a run was sent, one badge per channel. Renders nothing for a run that was not sent — a digest
 * with no channels, a quiet run, anything recorded before digests could publish — so those look exactly
 * as they did.
 */
export function RunDeliveries({ runId }: { runId: string }) {
  const { data: deliveries } = useRunDeliveries(runId)
  const { data: channels } = useChannels()
  if (!deliveries || deliveries.length === 0) return null

  const nameOf = (id: string) => channels?.find((channel) => channel.id === id)?.name ?? labels.common.unknown

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-[var(--border)] pt-2 text-xs">
      <span className="flex items-center gap-1.5 text-[var(--text-muted)]">
        <Send className="size-3.5 text-[var(--text-subtle)]" aria-hidden />
        {labels.digests.sentTo}
      </span>
      {deliveries.map((delivery) => (
        <span key={delivery.id} className="flex items-center gap-1.5" title={delivery.lastError ?? undefined}>
          <span className="text-[var(--text)]">{nameOf(delivery.channelId)}</span>
          <StateBadge state={presentDelivery(delivery)} size="sm" />
        </span>
      ))}
    </div>
  )
}
