/**
 * A parser for the Markdown our prompts ask models to emit — headings, bold, italic, inline code,
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
 *
 * The subset is wider than any single prompt asks for on purpose. Models routinely reach past what
 * they were told — the digest task prompt permits bold and flat lists, and the models answering it
 * emit italics and indented sub-bullets anyway — and an unparsed construct renders as its own syntax
 * (`*Languages:*`), which reads as a bug in the console rather than as a model overstepping.
 */

export interface TextRun {
  kind: 'text' | 'bold' | 'italic' | 'code'
  text: string
}

export interface CitationRun {
  kind: 'citation'
  /** 1-based index into the source list, as written by the model. */
  index: number
}

export type Inline = TextRun | CitationRun

/**
 * One entry in a list. `children` holds the sub-lists indented under it — empty for the flat case,
 * which is most of them.
 */
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
 * Emphasis, inline code, or a citation marker holding one or more comma-separated numbers.
 *
 * Ordered longest-first: `**bold**` has to be tried before `*italic*`, or every bold run parses as an
 * empty italic. The `_` forms require a non-word character on both sides so `snake_case_names` — which
 * turn up constantly in source text — are not read as emphasis.
 *
 * Both bracket families are accepted for citations. The prompt asks for ASCII `[1]`, but hosted models
 * routinely emit the fullwidth CJK pair `【1】` (and the fullwidth comma) regardless of what they were
 * told, and a marker that misses the pattern renders as dead text in the middle of the answer. A
 * mismatched pair like `[1】` is accepted for the same reason: a stray marker should still link.
 */
const INLINE_PATTERN =
  /\*\*(?<bold>[^*\n]+)\*\*|(?<![\w])__(?<boldAlt>[^_\n]+)__(?![\w])|\*(?<italic>[^*\n]+)\*|(?<![\w])_(?<italicAlt>[^_\n]+)_(?![\w])|`(?<code>[^`\n]+)`|[[【]\s*(?<cite>\d{1,3}(?:\s*[,，]\s*\d{1,3})*)\s*[\]】]/g

/** `#` is accepted alongside `##`/`###`: a lone top-level heading inside a card is still a heading. */
const HEADING = /^(#{1,3})\s+(.*)$/
const BULLET = /^(\s*)[-*+]\s+(.*)$/
const ORDERED = /^(\s*)\d{1,3}[.)]\s+(.*)$/
/** A table delimiter row: `|---|:--:|` and friends. */
const TABLE_DIVIDER = /^\s*\|?[\s|]*(?::?-{2,}:?[\s|]*)+\|?\s*$/

/** One list line before nesting is worked out. */
interface ListLine {
  indent: number
  ordered: boolean
  inline: Inline[]
}

/**
 * Split one line into text, emphasis, code and citation runs.
 *
 * A grouped marker becomes several citation runs, so `[1,3]` renders as two separate clickable chips.
 * That is the whole reason the prompt asks for grouping: one marker to read, still one link per source.
 * Repeats inside one marker collapse — `[1,1]` is the model stuttering, not two sources.
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
        // The numbering is 1-based, so a 0 names no source and can only render as an inert marker.
        if (index > 0 && !seen.has(index)) {
          seen.add(index)
          runs.push({ kind: 'citation', index })
        }
      }
      // Nothing citable in the marker — fall back to the literal text rather than deleting a
      // fragment of the answer, which is the passthrough rule this parser follows everywhere else.
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

/** Split a table row on unescaped pipes, dropping the empty edge cells `|a|b|` produces. */
function cells(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

/** Tabs count as four columns, so a tab-indented sub-bullet nests like a space-indented one. */
function indentWidth(prefix: string): number {
  return prefix.replace(/\t/g, '    ').length
}

/**
 * Fold a run of list lines into blocks, nesting anything indented past its predecessor.
 *
 * Returns several blocks rather than one when the marker type changes at the top level: a bullet list
 * directly followed by a numbered one is two lists, and rendering them as one would renumber or
 * re-bullet half of it.
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
        // Indented past this level: a sub-list belonging to the item above. An indented line with no
        // item above it (a reply that opens mid-nesting) is pulled up to this level instead of lost.
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
      // Gather the whole run of list lines — both markers, every indent — and let `buildLists` work
      // out the shape. Reading them one marker at a time cannot see that an indented bullet under a
      // numbered item is a child of it rather than the start of an unrelated list.
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
