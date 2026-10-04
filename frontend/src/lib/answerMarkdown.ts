
export interface TextRun {
  kind: 'text' | 'bold' | 'italic' | 'code'
  text: string
}

export interface CitationRun {
  kind: 'citation'
  /** 1-based, as the model writes it. */
  index: number
}

export type Inline = TextRun | CitationRun

export interface ListItem {
  inline: Inline[]
  children: Block[]
}

export type Block =
  | { kind: 'heading'; level: 2 | 3; inline: Inline[] }
  | { kind: 'paragraph'; inline: Inline[] }
  | { kind: 'list'; ordered: boolean; items: ListItem[] }
  | { kind: 'table'; header: Inline[][]; rows: Inline[][][] }

/**
 * Longest first, so `**bold**` is tried before `*italic*`; `_` needs a non-word character on both
 * sides so snake_case is not emphasis. Citations also accept fullwidth `【1】` and mismatched pairs,
 * which hosted models emit despite the prompt.
 */
const INLINE_PATTERN =
  /\*\*(?<bold>[^*\n]+)\*\*|(?<![\w])__(?<boldAlt>[^_\n]+)__(?![\w])|\*(?<italic>[^*\n]+)\*|(?<![\w])_(?<italicAlt>[^_\n]+)_(?![\w])|`(?<code>[^`\n]+)`|[[【]\s*(?<cite>\d{1,3}(?:\s*[,，]\s*\d{1,3})*)\s*[\]】]/g

const HEADING = /^(#{1,3})\s+(.*)$/
const BULLET = /^(\s*)[-*+]\s+(.*)$/
const ORDERED = /^(\s*)\d{1,3}[.)]\s+(.*)$/
/** A table delimiter row: `|---|:--:|` and friends. */
const TABLE_DIVIDER = /^\s*\|?[\s|]*(?::?-{2,}:?[\s|]*)+\|?\s*$/

interface ListLine {
  indent: number
  ordered: boolean
  inline: Inline[]
}

export function parseInline(line: string): Inline[] {
  const runs: Inline[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  INLINE_PATTERN.lastIndex = 0
  while ((match = INLINE_PATTERN.exec(line)) !== null) {
    if (match.index > lastIndex) {
      runs.push({ kind: 'text', text: line.slice(lastIndex, match.index) })
    }
    const groups = match.groups!
    const bold = groups.bold ?? groups.boldAlt
    const italic = groups.italic ?? groups.italicAlt
    if (bold !== undefined) {
      runs.push({ kind: 'bold', text: bold })
    } else if (italic !== undefined) {
      runs.push({ kind: 'italic', text: italic })
    } else if (groups.code !== undefined) {
      runs.push({ kind: 'code', text: groups.code })
    } else {
      const seen = new Set<number>()
      for (const number of groups.cite!.split(/[,，]/)) {
        const index = Number(number.trim())
        if (index > 0 && !seen.has(index)) {
          seen.add(index)
          runs.push({ kind: 'citation', index })
        }
      }
      if (seen.size === 0) {
        runs.push({ kind: 'text', text: match[0] })
      }
    }
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < line.length) {
    runs.push({ kind: 'text', text: line.slice(lastIndex) })
  }
  return runs
}

function cells(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

function indentWidth(prefix: string): number {
  return prefix.replace(/\t/g, '    ').length
}

/**
 * A change of marker type at the top level starts a new list; merging them would renumber half of
 * it.
 */
function buildLists(lines: ListLine[]): Block[] {
  const blocks: Block[] = []
  let cursor = 0

  const consume = (base: number, ordered: boolean): ListItem[] => {
    const items: ListItem[] = []
    while (cursor < lines.length) {
      const line = lines[cursor]
      if (line.indent < base) break
      if (line.indent > base) {
        // An indented line with no item above it (a reply that opens mid-nesting) is pulled up to
        // this level, not lost.
        if (items.length === 0) {
          items.push({ inline: line.inline, children: [] })
          cursor++
          continue
        }
        const childIndent = line.indent
        const childOrdered = line.ordered
        items[items.length - 1].children.push({
          kind: 'list',
          ordered: childOrdered,
          items: consume(childIndent, childOrdered),
        })
        continue
      }
      if (line.ordered !== ordered) break
      items.push({ inline: line.inline, children: [] })
      cursor++
    }
    return items
  }

  while (cursor < lines.length) {
    const { indent, ordered } = lines[cursor]
    blocks.push({ kind: 'list', ordered, items: consume(indent, ordered) })
  }
  return blocks
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
        level: heading[1].length >= 3 ? 3 : 2,
        inline: parseInline(heading[2]),
      })
      continue
    }

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
      const run: ListLine[] = []
      for (;;) {
        const asBullet = BULLET.exec(lines[i])
        const asOrdered = asBullet ? null : ORDERED.exec(lines[i])
        const match = asBullet ?? asOrdered
        if (!match) {
          i-- // the outer loop advances past the first non-item line
          break
        }
        run.push({
          indent: indentWidth(match[1]),
          ordered: asOrdered !== null,
          inline: parseInline(match[2]),
        })
        if (i + 1 >= lines.length) break
        i++
      }
      blocks.push(...buildLists(run))
      continue
    }

    paragraph.push(line.trim())
  }

  flushParagraph()
  return blocks
}
