import { useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { labels } from '@/config/labels'
import { taskPlaceholderNames, taskPlaceholders } from '@/config/tasks'
import { cn } from '@/lib/utils'

/**
 * A textarea for prompt text: known `{{placeholders}}` are tinted where they sit, typing `{{` offers
 * the ones that belong in this message, and anything the assistant will not fill in is called out
 * underneath.
 *
 * Written as a layer behind a transparent textarea rather than a `contenteditable` or an editor
 * dependency: the value stays a plain string, so undo, IME and the form's own state are untouched, and
 * the only cost is that the layer must carry the textarea's metrics exactly — hence `shared`.
 *
 * The layer paints backgrounds only, never text colour. Painting the text would mean hiding the
 * textarea's own, which takes the selection highlight with it.
 */

/** A finished `{{name}}`, matched the way `PromptTemplate.PLACEHOLDER` matches it. */
const PLACEHOLDER = /\{\{\s*([a-zA-Z0-9_]*)\s*\}\}/g

/** An unfinished one: `{{` and a partial name running up to the caret. */
const TYPING = /\{\{\s*([a-zA-Z0-9_]*)$/

/** Whatever closes the placeholder the caret sits inside, so completing it doesn't leave a tail. */
const CLOSING = /^[a-zA-Z0-9_]*\s*\}\}/

/** Every metric that decides where a glyph lands. The layer and the textarea must agree on all of it. */
const shared =
  'w-full rounded-lg border px-3 py-2 font-mono text-[12px] leading-relaxed whitespace-pre-wrap break-words'

interface Props {
  id: string
  /** Which message this is. Decides what the menu offers first — not what is accepted. */
  slot: 'system' | 'user'
  value: string
  onChange: (value: string) => void
  rows?: number
}

export function PlaceholderTextarea({ id, slot, value, onChange, rows = 10 }: Props) {
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const layerRef = useRef<HTMLDivElement>(null)
  const caretRef = useRef<HTMLSpanElement>(null)
  const dismissed = useRef(false)

  const [caret, setCaret] = useState(value.length)
  const [menu, setMenu] = useState<{ from: number; query: string } | null>(null)
  const [active, setActive] = useState(0)
  const [at, setAt] = useState({ left: 0, top: 0 })

  const options = menu ? matching(slot, menu.query) : []
  const open = menu !== null && options.length > 0
  const unknown = [...new Set(
    [...value.matchAll(PLACEHOLDER)]
      .map((match) => match[1])
      .filter((name) => !taskPlaceholderNames.includes(name)),
  )]

  // Measured rather than computed: the layer already mirrors the textarea's metrics, so a marker
  // rendered at the caret sits exactly where the caret does, wrapping and scrolling included.
  useLayoutEffect(() => {
    const marker = caretRef.current
    const input = inputRef.current
    if (!open || !marker || !input) return
    setAt({
      left: Math.min(marker.offsetLeft, Math.max(0, input.clientWidth - 280)),
      top: marker.offsetTop + marker.offsetHeight - input.scrollTop,
    })
  }, [open, menu?.from, menu?.query, value])

  /** Track where the caret is, and whether it is somewhere a suggestion would help. */
  const sync = (input: HTMLTextAreaElement) => {
    const position = input.selectionStart
    setCaret(position)
    const typing = TYPING.exec(value.slice(0, position))
    if (!typing || dismissed.current) {
      setMenu(null)
      return
    }
    setMenu({ from: typing.index, query: typing[1] })
    setActive(0)
  }

  const change = (next: string, input: HTMLTextAreaElement) => {
    dismissed.current = false
    onChange(next)
    // The value the handler reads is still the old one, so re-derive the menu from what was typed.
    const position = input.selectionStart
    const typing = TYPING.exec(next.slice(0, position))
    setCaret(position)
    setMenu(typing ? { from: typing.index, query: typing[1] } : null)
    setActive(0)
  }

  const insert = (name: string) => {
    const input = inputRef.current
    const position = Math.min(caret, value.length)
    const from = menu ? menu.from : position
    const rest = value.slice(position)
    // Completing `{{sour|ces}}` must not leave `ces}}` behind, and `{{|` must not gain a second `}}`.
    const closing = CLOSING.exec(rest)
    const text = `{{${name}}}`
    const next = value.slice(0, from) + text + (closing ? rest.slice(closing[0].length) : rest)

    dismissed.current = false
    onChange(next)
    setMenu(null)
    setCaret(from + text.length)
    queueMicrotask(() => {
      input?.focus()
      input?.setSelectionRange(from + text.length, from + text.length)
    })
  }

  const keyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : options.length - 1
      setActive((current) => (current + step) % options.length)
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      insert(options[active].name)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      dismissed.current = true
      setMenu(null)
    }
  }

  return (
    <div className="space-y-2">
      <div className="relative">
        <div
          ref={layerRef}
          aria-hidden
          className={cn(
            shared,
            'pointer-events-none absolute inset-0 overflow-hidden border-transparent text-transparent',
          )}
        >
          {paint(value, open ? caret : null, caretRef)}
        </div>

        <textarea
          ref={inputRef}
          id={id}
          rows={rows}
          value={value}
          onChange={(event) => change(event.target.value, event.target)}
          onClick={(event) => sync(event.currentTarget)}
          onKeyUp={(event) => sync(event.currentTarget)}
          onBlur={() => setMenu(null)}
          onScroll={(event) => {
            if (layerRef.current) layerRef.current.scrollTop = event.currentTarget.scrollTop
          }}
          onKeyDown={keyDown}
          spellCheck={false}
          role="combobox"
          aria-expanded={open}
          aria-autocomplete="list"
          aria-controls={`${id}-placeholders`}
          className={cn(
            shared,
            'relative resize-y bg-transparent text-[var(--text)] transition-colors hover:border-[var(--border-strong)] focus:border-[var(--accent)] focus:outline-none focus:ring-2 focus:ring-[var(--accent-ring)]',
            'border-[var(--border)]',
          )}
        />

        {open && (
          <ul
            id={`${id}-placeholders`}
            role="listbox"
            style={{ left: at.left, top: at.top }}
            className="absolute z-20 max-h-56 w-70 overflow-auto rounded-lg border border-[var(--border)] bg-[var(--surface)] py-1 shadow-[var(--shadow-md)]"
          >
            {options.map((option, index) => (
              <li key={option.name}>
                <button
                  type="button"
                  role="option"
                  aria-selected={index === active}
                  onMouseDown={(event) => event.preventDefault()}
                  onMouseEnter={() => setActive(index)}
                  onClick={() => insert(option.name)}
                  className={cn(
                    'block w-full px-2.5 py-1.5 text-left',
                    index === active && 'bg-[var(--surface-hover)]',
                  )}
                >
                  <span className="font-mono text-[12px] text-[var(--text)]">
                    {`{{${option.name}}}`}
                  </span>
                  <span className="mt-0.5 block text-[11px] leading-snug text-[var(--text-subtle)]">
                    {option.hint}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {unknown.length > 0 && (
        <p className="text-[11px] leading-relaxed text-[var(--tone-alert)]">
          {labels.tasks.placeholderUnknown}{' '}
          <span className="font-mono">{unknown.map((name) => `{{${name}}}`).join(', ')}</span>
        </p>
      )}

      <div className="rounded-lg border border-[var(--border)] bg-[var(--surface-sunken)] p-2.5">
        <p className="text-[11px] font-medium text-[var(--text-muted)]">
          {labels.tasks.placeholdersLabel}
        </p>
        <ul className="mt-1.5 space-y-1">
          {taskPlaceholders
            .filter((placeholder) => placeholder.slot === slot)
            .map((placeholder) => (
              <li key={placeholder.name} className="flex flex-wrap items-baseline gap-x-2">
                <button
                  type="button"
                  onClick={() => insert(placeholder.name)}
                  className="rounded bg-[var(--accent-subtle)] px-1.5 py-0.5 font-mono text-[11px] text-[var(--text)] hover:bg-[var(--surface-hover)]"
                >
                  {`{{${placeholder.name}}}`}
                </button>
                <span className="flex-1 text-[11px] leading-snug text-[var(--text-subtle)]">
                  {placeholder.hint}
                  {placeholder.required && (
                    <span className="ml-1 text-[var(--text-muted)]">
                      {labels.tasks.placeholderRequired}
                    </span>
                  )}
                </span>
              </li>
            ))}
        </ul>
        <p className="mt-2 text-[11px] text-[var(--text-subtle)]">{labels.tasks.placeholdersHint}</p>
      </div>
    </div>
  )
}

/**
 * What to offer for a partially typed name. This message's own come first and are normally all that
 * shows; the others are appended only when nothing here matches, so someone who deliberately types
 * `{{tod` in the user message still gets completed rather than stonewalled — the backend renders both
 * messages from one map, so that placeholder does work.
 */
function matching(slot: 'system' | 'user', query: string) {
  const hit = (name: string) => name.toLowerCase().startsWith(query.toLowerCase())
  const own = taskPlaceholders.filter((p) => p.slot === slot && hit(p.name))
  return own.length > 0 ? own : taskPlaceholders.filter((p) => p.slot !== slot && hit(p.name))
}

/**
 * The text, with each `{{name}}` wrapped so it can be tinted, and a zero-width marker dropped at the
 * caret when the menu needs somewhere to hang from.
 */
function paint(value: string, caret: number | null, marker: React.RefObject<HTMLSpanElement | null>) {
  const nodes: ReactNode[] = []
  let cursor = 0
  let key = 0
  let placed = false

  const push = (text: string, kind?: 'known' | 'unknown') => {
    const from = cursor
    cursor += text.length
    key += 1
    const className =
      kind === 'known'
        ? 'rounded bg-[var(--accent-subtle)]'
        : kind === 'unknown'
          ? 'rounded bg-[var(--tone-alert-bg)]'
          : undefined

    if (!placed && caret !== null && caret >= from && caret <= cursor) {
      const cut = caret - from
      placed = true
      nodes.push(
        <span key={key} className={className}>
          {text.slice(0, cut)}
          <span ref={marker} />
          {text.slice(cut)}
        </span>,
      )
      return
    }
    nodes.push(
      <span key={key} className={className}>
        {text}
      </span>,
    )
  }

  let last = 0
  for (const match of value.matchAll(PLACEHOLDER)) {
    const start = match.index ?? 0
    if (start > last) push(value.slice(last, start))
    push(match[0], taskPlaceholderNames.includes(match[1]) ? 'known' : 'unknown')
    last = start + match[0].length
  }
  push(value.slice(last))
  // Keeps the layer as tall as the textarea's content when that content ends in a newline.
  nodes.push(<span key="end">{'\u200b'}</span>)
  return nodes
}
