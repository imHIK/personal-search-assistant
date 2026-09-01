import { Sparkles } from 'lucide-react'
import { Fragment } from 'react'
import type { SearchHit } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card'
import { labels } from '@/config/labels'
import type { Block, Inline } from '@/lib/answerMarkdown'
import { parseAnswer } from '@/lib/answerMarkdown'
import { displayName } from '@/lib/utils'

/**
 * The grounded answer.
 *
 * The backend returns a plain string — there is no structured citation object and no HTML. It is
 * prompted to use a fixed Markdown subset (headings, bold, lists, tables) and to cite sources as `[n]`,
 * grouping several into one marker as `[1,3]`. Both are parsed here: the Markdown so a table of source
 * data renders as a table instead of a wall of pipes, and the markers so each citation becomes a button
 * that scrolls to the result it refers to — the difference between a citation a user can verify and one
 * they have to take on faith.
 *
 * See `lib/answerMarkdown.ts` for why the subset is parsed by hand rather than with a Markdown library.
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
  const blocks = parseAnswer(answer)

  return (
    <Card className="border-[var(--accent)]/25 bg-[var(--accent-subtle)]/40">
      <CardBody className="space-y-2.5">
        <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-[var(--accent)]">
          <Sparkles className="size-3.5" aria-hidden />
          {labels.search.answerHeading}
        </p>
        <div className="space-y-3 text-[15px] leading-relaxed text-[var(--text)]">
          {blocks.map((block, index) => (
            <BlockView key={index} block={block} hits={hits} onCitationClick={onCitationClick} />
          ))}
        </div>
      </CardBody>
    </Card>
  )
}

function BlockView({
  block,
  hits,
  onCitationClick,
}: {
  block: Block
  hits: SearchHit[]
  onCitationClick: (index: number) => void
}) {
  const runs = (inline: Inline[]) => (
    <InlineView inline={inline} hits={hits} onCitationClick={onCitationClick} />
  )

  switch (block.kind) {
    case 'heading':
      return block.level === 2 ? (
        <h3 className="text-base font-semibold text-[var(--text)]">{runs(block.inline)}</h3>
      ) : (
        <h4 className="text-sm font-semibold text-[var(--text)]">{runs(block.inline)}</h4>
      )

    case 'list':
      return block.ordered ? (
        <ol className="list-decimal space-y-1 pl-5">
          {block.items.map((item, index) => (
            <li key={index}>{runs(item)}</li>
          ))}
        </ol>
      ) : (
        <ul className="list-disc space-y-1 pl-5">
          {block.items.map((item, index) => (
            <li key={index}>{runs(item)}</li>
          ))}
        </ul>
      )

    case 'table':
      // Tabular source data is often wider than the card; it scrolls inside its own container so the
      // page itself never scrolls sideways.
      return (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr>
                {block.header.map((cell, index) => (
                  <th
                    key={index}
                    className="border-b border-[var(--border)] px-2 py-1.5 text-left font-semibold"
                  >
                    {runs(cell)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {row.map((cell, cellIndex) => (
                    <td
                      key={cellIndex}
                      className="border-b border-[var(--border)]/60 px-2 py-1.5 align-top"
                    >
                      {runs(cell)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )

    case 'paragraph':
      return <p>{runs(block.inline)}</p>
  }
}

function InlineView({
  inline,
  hits,
  onCitationClick,
}: {
  inline: Inline[]
  hits: SearchHit[]
  onCitationClick: (index: number) => void
}) {
  return (
    <>
      {inline.map((run, index) => {
        if (run.kind === 'citation') {
          return (
            <CitationChip
              key={index}
              index={run.index}
              hit={hits[run.index - 1]}
              onClick={onCitationClick}
            />
          )
        }
        if (run.kind === 'bold') {
          return (
            <strong key={index} className="font-semibold">
              {run.text}
            </strong>
          )
        }
        return <Fragment key={index}>{run.text}</Fragment>
      })}
    </>
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
