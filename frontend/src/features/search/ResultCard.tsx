import { Copy, ExternalLink } from 'lucide-react'
import { forwardRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import type { Blob, SearchHit } from '@/api/types'
import { Technical, TechnicalPanel, useTechnicalDetails } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { connectorFor, type ResultFieldSpec } from '@/config/connectors'
import { labels } from '@/config/labels'
import { useKnowledgeList } from '@/hooks/queries'
import { cn, copyToClipboard, displayName, highlightSegments, relativeTime } from '@/lib/utils'

/**
 * The metadata a connector says is worth showing on a result, formatted in its order. Absent values
 * are skipped, and an option value the descriptor does not know is hidden rather than shown raw — an
 * enum name belongs behind technical details.
 */
function resultFacts(fields: ResultFieldSpec[] | undefined, metadata: Blob | null | undefined): string[] {
  const facts: string[] = []
  for (const field of fields ?? []) {
    const value = metadata?.[field.key]
    if (value === null || value === undefined || value === '') continue
    switch (field.kind) {
      case 'text':
        if (typeof value === 'string' || typeof value === 'number') facts.push(String(value))
        break
      case 'flag':
        if (value === true && field.label) facts.push(field.label)
        break
      case 'option': {
        const option = field.options?.find((candidate) => candidate.value === value)
        if (option) facts.push(option.label)
        break
      }
      case 'date': {
        const iso = typeof value === 'number' ? new Date(value).toISOString() : String(value)
        const when = relativeTime(iso)
        if (when) facts.push(field.label ? `${field.label} ${when}` : when)
        break
      }
    }
  }
  return facts
}

/** A leg rank for the technical panel; a leg that did not return the chunk shows a dash, not a zero. */
function rankLabel(rank: number | null): string {
  return rank === null ? '—' : `#${rank}`
}

/** An excerpt with the query's terms marked. */
function Excerpt({ text, query }: { text: string; query: string }) {
  return (
    <>
      {highlightSegments(text, query).map((segment, index) => (
        <span
          key={index}
          className={cn(
            segment.match && 'rounded bg-[var(--tone-wait-bg)] px-0.5 font-medium text-[var(--text)]',
          )}
        >
          {segment.text}
        </span>
      ))}
    </>
  )
}

/**
 * One result — an item, not a passage. Its best-matching excerpt shows by default; the item's other
 * matching passages sit behind a disclosure, so a long document that matches in five places is one
 * card rather than five near-identical ones.
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
  /** Briefly set after a citation in the answer jumps here, so the arrival is visible. */
  highlighted?: boolean
}>(function ResultCard({ hit, rank, query, topScore, highlighted }, ref) {
  const technical = useTechnicalDetails()
  const { data: sources } = useKnowledgeList()
  const [showMatches, setShowMatches] = useState(false)

  const source = sources?.find((knowledge) => knowledge.id === hit.knowledgeId)
  const descriptor = source ? connectorFor(source.connectorDetails.type) : null
  const Icon = descriptor?.icon
  const name = displayName(hit)
  const facts = resultFacts(descriptor?.resultFields, hit.metadata)
  const relative = topScore > 0 ? Math.max(6, Math.round((hit.score / topScore) * 100)) : 0

  return (
    <Card
      ref={ref as React.Ref<HTMLDivElement>}
      // Focusable but not in the tab order: a citation jump moves focus here so a screen reader and
      // the keyboard both follow the scroll, without adding a stop to every card on the page.
      id={`hit-${rank}`}
      tabIndex={-1}
      className={cn(
        'scroll-mt-24 px-5 py-4 outline-none transition-shadow duration-300',
        highlighted && 'ring-2 ring-[var(--accent)]',
      )}
    >
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

          {facts.length > 0 && (
            <p className="text-xs text-[var(--text-muted)]">{facts.join(' · ')}</p>
          )}

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
          <Excerpt text={hit.snippet} query={query} />
        </p>
      )}

      {hit.moreMatches.length > 0 && (
        <div className="mt-2">
          <button
            type="button"
            onClick={() => setShowMatches((open) => !open)}
            aria-expanded={showMatches}
            className="text-xs text-[var(--text-subtle)] transition-colors hover:text-[var(--accent)]"
          >
            {showMatches ? labels.search.hideMatches : labels.search.moreMatches(hit.moreMatches.length)}
          </button>
          {showMatches && (
            <ul className="mt-2 space-y-2 border-l-2 border-[var(--border)] pl-3">
              {hit.moreMatches.map((match) =>
                match.snippet ? (
                  <li
                    key={match.chunkId}
                    className="line-clamp-3 text-sm leading-relaxed text-[var(--text-muted)]"
                  >
                    <Excerpt text={match.snippet} query={query} />
                  </li>
                ) : null,
              )}
            </ul>
          )}
        </div>
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
            ['ordinal', String(hit.ordinal)],
            // Why this result sits where it does, stage by stage — the thing to read before changing a
            // search setting.
            ['lexicalRank', rankLabel(hit.ranking.lexicalRank)],
            ['vectorRank', rankLabel(hit.ranking.vectorRank)],
            ['retrievalScore', hit.ranking.retrievalScore.toFixed(6)],
            ['groupedScore', hit.ranking.groupedScore.toFixed(6)],
            ['recencyFactor', `×${hit.ranking.recencyFactor.toFixed(3)}`],
            [
              'matchedChunks',
              [hit, ...hit.moreMatches]
                .map((match) => `#${match.ordinal} (${(match === hit ? hit.ranking.retrievalScore : match.score).toFixed(4)})`)
                .join(', '),
            ],
            ['score', hit.score.toFixed(6)],
            ['uri', hit.uri ?? '—'],
            ['metadata', JSON.stringify(hit.metadata)],
          ]}
        />
      </Technical>
    </Card>
  )
})
