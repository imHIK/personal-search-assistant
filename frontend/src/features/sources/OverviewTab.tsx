import { Link } from 'react-router-dom'
import type { CursorInfo, Knowledge } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Card, CardBody, CardHeader, CardTitle, StatTile } from '@/components/ui/Card'
import { ProgressBar } from '@/components/ui/States'
import { connectorFor } from '@/config/connectors'
import { labels } from '@/config/labels'
import { presentSource } from '@/config/presentation'
import { useConnections } from '@/hooks/queries'
import { absoluteTime, formatNumber, relativeTime } from '@/lib/utils'

export function OverviewTab({
  knowledge,
  cursors,
  onOpenItems,
}: {
  knowledge: Knowledge
  cursors?: CursorInfo[]
  onOpenItems: () => void
}) {
  const { entities, indexed, failed } = knowledge.stats
  const processing = Math.max(0, entities - indexed - failed)
  const descriptor = connectorFor(knowledge.connectorDetails.type)
  const { data: connections } = useConnections()
  const boundConnection = connections?.find(
    (connection) => connection.id === knowledge.connectorDetails.connectionId,
  )

  const nextCheck = knowledge.nextSyncDueAt
    ? relativeTime(knowledge.nextSyncDueAt)
    : labels.sources.dueNow
  const schedule = describeSchedule(knowledge)

  return (
    <div className="space-y-5">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatTile
          label={labels.detail.searchable}
          value={formatNumber(indexed)}
          tone="ok"
          onClick={onOpenItems}
        />
        <StatTile
          label={labels.detail.processing}
          value={formatNumber(processing)}
          tone={processing > 0 ? 'busy' : 'neutral'}
          onClick={onOpenItems}
        />
        <StatTile
          label={labels.detail.failed}
          value={formatNumber(failed)}
          tone={failed > 0 ? 'alert' : 'neutral'}
          onClick={onOpenItems}
        />
      </div>

      {entities > 0 && indexed < entities && (
        <div className="space-y-2">
          <ProgressBar
            value={indexed}
            max={entities}
            tone="busy"
            label={labels.detail.progress(indexed, entities)}
          />
          <p className="text-xs text-[var(--text-muted)]">
            {labels.detail.progress(indexed, entities)}
          </p>
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardBody>
          <dl className="grid gap-x-8 gap-y-3 text-sm sm:grid-cols-2">
            <Row label="Type" value={descriptor.label} />
            <Row
              label="Account"
              value={
                boundConnection ? (
                  <Link
                    to={`/connections/${boundConnection.id}`}
                    className="text-[var(--accent)] hover:underline"
                  >
                    {boundConnection.name}
                  </Link>
                ) : descriptor.requiresConnection ? (
                  'Default for this service'
                ) : (
                  'Not needed'
                )
              }
            />
            <Row label="Checks" value={schedule} />
            <Row label={labels.sources.nextCheck} value={nextCheck ?? labels.sources.dueNow} />
            <Row
              label={labels.sources.lastChecked}
              value={relativeTime(knowledge.updatedAt) ?? labels.sources.never}
            />
            <Row label="Connected" value={relativeTime(knowledge.createdAt) ?? '—'} />
            <Row label="Included" value={describeInputs(knowledge)} />
          </dl>
        </CardBody>
      </Card>

      <Technical>
        <TechnicalPanel
          rows={[
            ['id', knowledge.id],
            ['status', knowledge.status],
            ['anchor', absoluteTime(knowledge.anchor)],
            ['nextSyncDueAt', absoluteTime(knowledge.nextSyncDueAt) ?? 'null (due now)'],
            ['syncGeneration', formatNumber(knowledge.syncGeneration)],
            ['connectorDetails.type', knowledge.connectorDetails.type],
            ['connectorDetails.connectionId', knowledge.connectorDetails.connectionId ?? 'null'],
            ['inputs', JSON.stringify(knowledge.inputs)],
            ['config.chunking', JSON.stringify(knowledge.config.chunking)],
            ['config.scheduleSettings', JSON.stringify(knowledge.config.scheduleSettings)],
            ['config.backfill', JSON.stringify(knowledge.config.backfill)],
            ['derived state', presentSource(knowledge, cursors).label],
            ['cursors', cursors ? String(cursors.length) : 'not loaded'],
            ['lastError', knowledge.lastError ?? '—'],
          ]}
        />
      </Technical>
    </div>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-[var(--text-subtle)]">{label}</dt>
      <dd className="mt-0.5 break-words text-[var(--text)]">{value}</dd>
    </div>
  )
}

/** Turn the schedule settings into a sentence rather than exposing cron/interval strings. */
function describeSchedule(knowledge: Knowledge): string {
  const { cron, interval, enabled } = knowledge.config.scheduleSettings
  if (!enabled) return labels.schedule.manual
  if (cron) return `Custom schedule (${cron})`
  if (!interval) return 'On the default schedule'
  const readable: Record<string, string> = {
    '15m': labels.schedule.every15m,
    '1h': labels.schedule.hourly,
    '6h': labels.schedule.every6h,
    '1d': labels.schedule.daily,
  }
  return readable[interval] ?? `Every ${interval}`
}

/** Summarise `inputs` using the connector's own field labels, not raw keys. */
function describeInputs(knowledge: Knowledge): string {
  const descriptor = connectorFor(knowledge.connectorDetails.type)
  const parts: string[] = []

  for (const field of descriptor.inputFields) {
    const value = knowledge.inputs[field.name]
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) {
      if (value.length === 0) continue
      parts.push(`${field.label}: ${value.join(', ')}`)
    } else {
      parts.push(`${field.label}: ${String(value)}`)
    }
  }

  return parts.length > 0 ? parts.join(' · ') : 'Everything'
}
