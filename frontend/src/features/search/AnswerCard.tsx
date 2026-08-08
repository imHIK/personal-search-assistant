import { Sparkles } from 'lucide-react'
import { Fragment } from 'react'
import type { SearchHit } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card'
import { labels } from '@/config/labels'
import { displayName } from '@/lib/utils'

/**
 * The grounded answer.
 *
 * The backend returns a plain string that cites sources as `[n]`, 1-based into `hits` — there is
 * no structured citation object. So the markers are parsed out here and turned into buttons that
 * scroll to the result they refer to, which is the difference between a citation a user can
 * verify and one they have to take on faith.
 */
export function AnswerCard({
  answer,
  hits,
  onCitationClick,
}: {
  answer: string
  hits: SearchHit[]
  onCitationClick: (index: number) => void
}) {
  const segments = parseCitations(answer)

  return (
    <Card className="border-[var(--accent)]/25 bg-[var(--accent-subtle)]/40">
      <CardBody className="space-y-2.5">
        <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-[var(--accent)]">
          <Sparkles className="size-3.5" aria-hidden />
          {labels.search.answerHeading}
        </p>
        <p className="whitespace-pre-wrap text-[15px] leading-relaxed text-[var(--text)]">
          {segments.map((segment, index) =>
            segment.citation === undefined ? (
              <Fragment key={index}>{segment.text}</Fragment>
            ) : (
              <CitationChip
                key={index}
                index={segment.citation}
                hit={hits[segment.citation - 1]}
                onClick={onCitationClick}
              />
            ),
          )}
        </p>
      </CardBody>
    </Card>
  )
}

function CitationChip({
  index,
  hit,
  onClick,
}: {
  index: number
  hit: SearchHit | undefined
  onClick: (index: number) => void
}) {
  // The model can cite a number outside the hit list. Render it inert rather than linking to
  // something that isn't there.
  if (!hit) {
    return <sup className="text-[var(--text-subtle)]">[{index}]</sup>
  }

  return (
    <button
      type="button"
      onClick={() => onClick(index)}
      title={displayName(hit)}
      aria-label={`${labels.search.citation(index)}: ${displayName(hit)}`}
      className="mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-[var(--accent)] px-1.5 align-[0.1em] text-[11px] font-semibold text-[var(--accent-fg)] transition-opacity hover:opacity-85"
    >
      {index}
    </button>
  )
}

interface Segment {
  text: string
  /** 1-based index into hits, when this segment is a citation marker. */
  citation?: number
}

/** Split an answer on `[n]` markers, keeping the surrounding prose intact. */
function parseCitations(answer: string): Segment[] {
  const segments: Segment[] = []
  const pattern = /\[(\d{1,2})\]/g
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(answer)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ text: answer.slice(lastIndex, match.index) })
    }
    segments.push({ text: match[0], citation: Number(match[1]) })
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < answer.length) {
    segments.push({ text: answer.slice(lastIndex) })
  }
  return segments
}
