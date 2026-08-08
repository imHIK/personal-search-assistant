import { Copy, ExternalLink } from 'lucide-react'
import { forwardRef } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import type { SearchHit } from '@/api/types'
import { Technical, TechnicalPanel, useTechnicalDetails } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { connectorFor } from '@/config/connectors'
import { labels } from '@/config/labels'
import { useKnowledgeList } from '@/hooks/queries'
import { cn, copyToClipboard, displayName, highlightSegments } from '@/lib/utils'

/**
 * One result.
 *
 * The relevance bar is deliberately relative to the top hit rather than absolute: the score is a
 * raw RRF value with no calibration behind it (the reranker is a passthrough), so an absolute
 * number would imply a precision that isn't there. The raw score is available under Technical
 * details for anyone who wants it.
 */
export const ResultCard = forwardRef<HTMLElement, {
  hit: SearchHit
  rank: number
  query: string
  topScore: number
}>(function ResultCard({ hit, rank, query, topScore }, ref) {
  const technical = useTechnicalDetails()
  const { data: sources } = useKnowledgeList()

  const source = sources?.find((knowledge) => knowledge.id === hit.knowledgeId)
  const descriptor = source ? connectorFor(source.connectorDetails.type) : null
  const Icon = descriptor?.icon
  const name = displayName(hit)
  const relative = topScore > 0 ? Math.max(6, Math.round((hit.score / topScore) * 100)) : 0

  return (
    <Card ref={ref as React.Ref<HTMLDivElement>} className="scroll-mt-24 px-5 py-4">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex items-baseline gap-2">
            <span className="text-xs font-medium tabular-nums text-[var(--text-subtle)]">
              {rank}
            </span>
            <h3 className="min-w-0 truncate text-sm font-medium text-[var(--text)]" title={name}>
              {name}
            </h3>
          </div>

          {source && (
            <Link
              to={`/knowledge/${source.id}`}
              className="inline-flex items-center gap-1.5 text-xs text-[var(--text-muted)] transition-colors hover:text-[var(--accent)]"
            >
              {Icon && <Icon className="size-3.5" aria-hidden />}
              {source.name}
            </Link>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1">
          {hit.uri && (
            <>
              <Button
                variant="ghost"
                size="iconSm"
                title={labels.search.copyLink}
                aria-label={`${labels.search.copyLink} — ${name}`}
                onClick={() =>
                  void copyToClipboard(hit.uri!).then((ok) =>
                    ok ? toast.success(labels.common.copied) : toast.error('Could not copy'),
                  )
                }
              >
                <Copy />
              </Button>
              <Button asChild variant="ghost" size="iconSm" title={labels.search.openOriginal}>
                <a href={hit.uri} target="_blank" rel="noreferrer" aria-label={`${labels.search.openOriginal} — ${name}`}>
                  <ExternalLink />
                </a>
              </Button>
            </>
          )}
        </div>
      </div>

      {hit.snippet && (
        <p className="mt-2.5 line-clamp-3 text-sm leading-relaxed text-[var(--text-muted)]">
          {highlightSegments(hit.snippet, query).map((segment, index) => (
            <span
              key={index}
              className={cn(
                segment.match && 'rounded bg-[var(--tone-wait-bg)] px-0.5 font-medium text-[var(--text)]',
              )}
            >
              {segment.text}
            </span>
          ))}
        </p>
      )}

      <div className="mt-3 flex items-center gap-3">
        <div
          className="h-1 w-24 overflow-hidden rounded-full bg-[var(--tone-neutral-bg)]"
          title="Relevance, relative to the best result"
        >
          <div
            className="h-full rounded-full bg-[var(--accent)]"
            style={{ width: `${relative}%` }}
          />
        </div>
        {technical && (
          <span className="font-mono text-[11px] text-[var(--text-subtle)]">
            score {hit.score.toFixed(4)}
          </span>
        )}
        {source && (
          <Link
            to={`/knowledge/${source.id}?tab=items`}
            className="ml-auto text-xs text-[var(--text-subtle)] transition-colors hover:text-[var(--accent)]"
          >
            {labels.search.viewItem}
          </Link>
        )}
      </div>

      <Technical className="mt-3">
        <TechnicalPanel
          rows={[
            ['chunkId', hit.chunkId],
            ['entityId', hit.entityId],
            ['knowledgeId', hit.knowledgeId],
            ['score', hit.score.toFixed(6)],
            ['uri', hit.uri ?? '—'],
            ['metadata', JSON.stringify(hit.metadata)],
          ]}
        />
      </Technical>
    </Card>
  )
})
