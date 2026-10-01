import { Check, ExternalLink, EyeOff, Eye } from 'lucide-react'
import type { Blob, EntityRecord } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { connectorFor } from '@/config/connectors'
import { jobDashboard, type JobFilterSpec } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { resultFacts } from '@/lib/resultFacts'
import { absoluteTime, cn, relativeTime } from '@/lib/utils'

const { applied: APPLIED, hidden: HIDDEN } = jobDashboard.marks

export function JobRow({
  item,
  enrichedSpecs,
  pending,
  onMark,
}: {
  item: EntityRecord
  enrichedSpecs: JobFilterSpec[]
  pending: boolean
  onMark: (values: Blob) => void
}) {
  const facts = resultFacts(connectorFor(jobDashboard.sourceType)?.resultFields, item.metadata)
  const href = text(item.metadata.applyUrl) ?? item.uri ?? undefined
  const applied = Boolean(item.custom[APPLIED])
  const hidden = item.custom[HIDDEN] === true
  const added = relativeTime(item.createdAt)
  const enriched = enrichedSpecs
    .map((spec) => [spec.label, display(item.enriched[spec.label], spec)] as const)
    .filter(([, value]) => value !== null)

  return (
    <Card className={cn('px-5 py-4', hidden && 'opacity-60')}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1.5">
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex max-w-full items-center gap-1.5 text-sm font-medium text-[var(--text)] hover:text-[var(--accent)]"
            title={labels.jobs.open}
          >
            <span className="truncate">{item.title ?? item.externalId}</span>
            <ExternalLink className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
          </a>
          {facts.length > 0 && (
            <p className="line-clamp-2 text-xs text-[var(--text-muted)]" title={facts.join(' · ')}>
              {facts.join(' · ')}
            </p>
          )}
          {enriched.length > 0 && (
            <div className="flex flex-wrap gap-1.5 pt-0.5">
              {enriched.map(([name, value]) => (
                <span
                  key={name}
                  className="rounded-md bg-[var(--bg-subtle)] px-2 py-0.5 text-[11px] text-[var(--text-muted)]"
                >
                  <span className="text-[var(--text-subtle)]">{name}</span> {value}
                </span>
              ))}
            </div>
          )}
          {added && <p className="text-[11px] text-[var(--text-subtle)]">{labels.jobs.firstSeen(added)}</p>}
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          <Button
            variant={applied ? 'secondary' : 'ghost'}
            size="sm"
            disabled={pending}
            onClick={() => onMark({ [APPLIED]: applied ? null : new Date().toISOString() })}
            title={applied ? labels.jobs.unmarkAppliedHint : undefined}
          >
            {applied && <Check />}
            {applied ? labels.jobs.unmarkApplied : labels.jobs.markApplied}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={pending}
            onClick={() => onMark({ [HIDDEN]: hidden ? null : true })}
          >
            {hidden ? <Eye /> : <EyeOff />}
            {hidden ? labels.jobs.unhide : labels.jobs.hide}
          </Button>
        </div>
      </div>

      <Technical className="mt-3">
        <TechnicalPanel
          rows={[
            ['id', item.id],
            ['knowledgeId', item.knowledgeId],
            ['iterableId', item.iterableId ?? '—'],
            ['externalId', item.externalId],
            ['platform', text(item.metadata.platform) ?? '—'],
            ['board', text(item.metadata.board) ?? '—'],
            ['createdAt', absoluteTime(item.createdAt)],
            ['custom', JSON.stringify(item.custom)],
            ['enrichmentError', item.enrichmentError ?? '—'],
          ]}
        />
      </Technical>
    </Card>
  )
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function display(value: unknown, spec: JobFilterSpec): string | null {
  if (value === null || value === undefined || value === '') return null
  if (Array.isArray(value)) return value.length ? value.join(', ') : null
  if (typeof value === 'boolean') {
    return spec.options?.find((o) => o.value === (value ? 'yes' : 'no'))?.label ?? String(value)
  }
  return String(value)
}
