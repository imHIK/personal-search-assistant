import { Fragment, useMemo } from 'react'
import type { Block, Inline } from '@/lib/answerMarkdown'
import { parseAnswer } from '@/lib/answerMarkdown'
import { cn } from '@/lib/utils'

/**
 * Where a `[n]` marker points. `label` is what the chip announces — the title of the source it
 * names — and `onClick` is what brings that source into view.
 */
export interface Citation {
  label: string
  onClick: () => void
}

/**
 * Renders the Markdown subset our prompts produce, with `[n]` markers turned into buttons.
 *
 * Shared by the search answer and the digest task summary. Both get the same string shape from the
 * same family of prompts and the same numbered-sources contract, so they get the same renderer:
 * the only thing that differs is what a citation jumps to, which is `citation`'s business.
 *
 * Nothing here is ever interpreted as HTML — see `lib/answerMarkdown.ts`.
 */
export function Markdown({
  text,
  citation,
  className,
}: {
  text: string
  /** Resolve a 1-based marker to something clickable; return null to render it as inert text. */
  citation?: (index: number) => Citation | null
  className?: string
}) {
  const blocks = useMemo(() => parseAnswer(text), [text])

  return (
    <div className={cn('space-y-3 leading-relaxed', className)}>
      {blocks.map((block, index) => (
        <BlockView key={index} block={block} citation={citation} />
      ))}
    </div>
  )
}

type Resolve = ((index: number) => Citation | null) | undefined

function BlockView({ block, citation }: { block: Block; citation: Resolve }) {
  const runs = (inline: Inline[]) => <InlineView inline={inline} citation={citation} />

  switch (block.kind) {
    case 'heading':
      return block.level === 2 ? (
        <h3 className="text-[1.05em] font-semibold text-[var(--text)]">{runs(block.inline)}</h3>
      ) : (
        <h4 className="font-semibold text-[var(--text)]">{runs(block.inline)}</h4>
      )

    case 'list':
      return <ListView block={block} citation={citation} />

    case 'table':
      // Tabular source data is often wider than its container; it scrolls inside its own box so the
      // page itself never scrolls sideways.
      return (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
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

/**
 * A list and whatever is nested under it.
 *
 * `space-y-1` on the outer list only: a sub-list is part of its parent item, so spacing it away from
 * the line it belongs to reads as a sibling rather than a child.
 */
function ListView({
  block,
  citation,
}: {
  block: Extract<Block, { kind: 'list' }>
  citation: Resolve
}) {
  const Tag = block.ordered ? 'ol' : 'ul'
  return (
    <Tag className={cn('space-y-1 pl-5', block.ordered ? 'list-decimal' : 'list-disc')}>
      {block.items.map((item, index) => (
        <li key={index}>
          <InlineView inline={item.inline} citation={citation} />
          {item.children.map((child, childIndex) => (
            <div key={childIndex} className="mt-1">
              <BlockView block={child} citation={citation} />
            </div>
          ))}
        </li>
      ))}
    </Tag>
  )
}

function InlineView({ inline, citation }: { inline: Inline[]; citation: Resolve }) {
  return (
    <>
      {inline.map((run, index) => {
        switch (run.kind) {
          case 'citation':
            return <CitationChip key={index} index={run.index} target={citation?.(run.index) ?? null} />
          case 'bold':
            return (
              <strong key={index} className="font-semibold">
                {run.text}
              </strong>
            )
          case 'italic':
            return <em key={index}>{run.text}</em>
          case 'code':
            return (
              <code
                key={index}
                className="rounded bg-[var(--surface-sunken)] px-1 py-0.5 font-mono text-[0.9em]"
              >
                {run.text}
              </code>
            )
          default:
            return <Fragment key={index}>{run.text}</Fragment>
        }
      })}
    </>
  )
}

function CitationChip({ index, target }: { index: number; target: Citation | null }) {
  // The model can cite a number outside the source list. Render it inert rather than linking to
  // something that isn't there.
  if (!target) {
    return <sup className="text-[var(--text-subtle)]">[{index}]</sup>
  }

  return (
    <button
      type="button"
      onClick={target.onClick}
      title={target.label}
      aria-label={target.label}
      className="mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-[var(--accent)] px-1.5 align-[0.1em] text-[11px] font-semibold text-[var(--accent-fg)] transition-opacity hover:opacity-85"
    >
      {index}
    </button>
  )
}
