/**
 * A parser for exactly the Markdown the answer prompt asks the model to emit — headings, bold,
 * bullet/numbered lists and tables — plus the `[n]` / `[1,2,3]` citation markers.
 *
 * Deliberately hand-written rather than a Markdown dependency. The prompt constrains the output to a
 * known subset, so a full CommonMark implementation would be a large dependency for a small grammar,
 * and every renderer worth having also wants raw-HTML support that we would then have to sanitise. The
 * output here is plain data: block descriptors with inline runs, rendered as React elements by the
 * caller. Nothing is ever interpreted as HTML.
 *
 * Anything outside the subset is passed through as literal text rather than dropped — an unexpected
 * construct should look slightly plain, never disappear from the answer.
 */

export interface TextRun {
  kind: 'text' | 'bold'
  text: string
}

export interface CitationRun {
  kind: 'citation'
  /** 1-based index into the hit list, as written by the model. */
  index: number
}

export type Inline = TextRun | CitationRun

export type Block =
  | { kind: 'heading'; level: 2 | 3; inline: Inline[] }
  | { kind: 'paragraph'; inline: Inline[] }
  | { kind: 'list'; ordered: boolean; items: Inline[][] }
  | { kind: 'table'; header: Inline[][]; rows: Inline[][][] }

/** `**bold**`, or a citation marker holding one or more comma-separated numbers. */
const INLINE_PATTERN = /\*\*([^*]+)\*\*|\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\]/g

const HEADING = /^(#{2,3})\s+(.*)$/
const BULLET = /^\s*[-*]\s+(.*)$/
const ORDERED = /^\s*\d{1,3}[.)]\s+(.*)$/
/** A table delimiter row: `|---|:--:|` and friends. */
const TABLE_DIVIDER = /^\s*\|?[\s|]*(?::?-{2,}:?[\s|]*)+\|?\s*$/

/**
 * Split one line into text, bold and citation runs.
 *
 * A grouped marker becomes several citation runs, so `[1,3]` renders as two separate clickable chips.
 * That is the whole reason the prompt asks for grouping: one marker to read, still one link per source.
 */
export function parseInline(line: string): Inline[] {
  const runs: Inline[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  INLINE_PATTERN.lastIndex = 0
  while ((match = INLINE_PATTERN.exec(line)) !== null) {
    if (match.index > lastIndex) {
      runs.push({ kind: 'text', text: line.slice(lastIndex, match.index) })
    }
    if (match[1] !== undefined) {
      runs.push({ kind: 'bold', text: match[1] })
    } else {
      for (const number of match[2].split(',')) {
        runs.push({ kind: 'citation', index: Number(number.trim()) })
      }
    }
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < line.length) {
    runs.push({ kind: 'text', text: line.slice(lastIndex) })
  }
  return runs
}

/** Split a table row on unescaped pipes, dropping the empty edge cells `|a|b|` produces. */
function cells(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

export function parseAnswer(answer: string): Block[] {
  const lines = answer.replace(/\r\n/g, '\n').split('\n')
  const blocks: Block[] = []
  let paragraph: string[] = []

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push({ kind: 'paragraph', inline: parseInline(paragraph.join(' ')) })
      paragraph = []
    }
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    if (line.trim() === '') {
      flushParagraph()
      continue
    }

    const heading = HEADING.exec(line)
    if (heading) {
      flushParagraph()
      blocks.push({
        kind: 'heading',
        level: heading[1].length === 2 ? 2 : 3,
        inline: parseInline(heading[2]),
      })
      continue
    }

    // A table needs its delimiter row to be a table at all; without one the pipes are just text.
    if (line.includes('|') && i + 1 < lines.length && TABLE_DIVIDER.test(lines[i + 1])) {
      flushParagraph()
      const header = cells(line).map(parseInline)
      const rows: Inline[][][] = []
      i += 2
      while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
        rows.push(cells(lines[i]).map(parseInline))
        i++
      }
      i-- // the outer loop advances past the first non-row line
      blocks.push({ kind: 'table', header, rows })
      continue
    }

    const bullet = BULLET.exec(line)
    const ordered = bullet ? null : ORDERED.exec(line)
    if (bullet || ordered) {
      flushParagraph()
      const isOrdered = ordered !== null
      const items: Inline[][] = [parseInline((bullet ?? ordered)![1])]
      while (i + 1 < lines.length) {
        const next = isOrdered ? ORDERED.exec(lines[i + 1]) : BULLET.exec(lines[i + 1])
        if (!next) {
          break
        }
        items.push(parseInline(next[1]))
        i++
      }
      blocks.push({ kind: 'list', ordered: isOrdered, items })
      continue
    }

    paragraph.push(line.trim())
  }

  flushParagraph()
  return blocks
}
