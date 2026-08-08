import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** "3 minutes ago" / "in 2 hours". Returns null for a null instant so callers can pick a fallback. */
export function relativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return null

  const deltaSeconds = Math.round((then - Date.now()) / 1000)
  const absolute = Math.abs(deltaSeconds)

  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['second', 60],
    ['minute', 60],
    ['hour', 24],
    ['day', 7],
    ['week', 4.35],
    ['month', 12],
    ['year', Infinity],
  ]

  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  if (absolute < 45) return formatter.format(deltaSeconds, 'second')

  let value = deltaSeconds
  for (const [unit, step] of units) {
    if (Math.abs(value) < step) return formatter.format(Math.round(value), unit)
    value /= step
  }
  return formatter.format(Math.round(value), 'year')
}

/** Absolute timestamp for tooltips and technical details. */
export function absoluteTime(iso: string | null | undefined): string | null {
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

export function formatNumber(value: number): string {
  return value.toLocaleString()
}

/** Milliseconds → "0.3" for "12 results in 0.3s". */
export function formatSeconds(ms: number): string {
  if (ms < 100) return (ms / 1000).toFixed(2)
  return (ms / 1000).toFixed(1)
}

/** Filename or last path segment, for items whose only identity is a path or URI. */
export function basename(value: string | null | undefined): string | null {
  if (!value) return null
  const withoutQuery = value.split(/[?#]/)[0]
  const segments = withoutQuery.split(/[/\\]/).filter(Boolean)
  const last = segments.pop()
  return last ? decodeURIComponent(last) : null
}

/** Best available human name for an item, falling back through the identifiers we have. */
export function displayName(item: {
  title?: string | null
  uri?: string | null
  externalId?: string | null
}): string {
  return (
    item.title?.trim() ||
    basename(item.uri) ||
    basename(item.externalId) ||
    item.externalId ||
    'Untitled'
  )
}

export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/** Split text on the query's words so matches can be marked without dangerouslySetInnerHTML. */
export function highlightSegments(text: string, query: string): { text: string; match: boolean }[] {
  const terms = query
    .split(/\s+/)
    .map((term) => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .filter((term) => term.length > 2)

  if (terms.length === 0) return [{ text, match: false }]

  const pattern = new RegExp(`(${terms.join('|')})`, 'gi')
  return text
    .split(pattern)
    .filter((part) => part !== '')
    .map((part) => ({ text: part, match: pattern.test(part) && terms.some((t) => new RegExp(`^${t}$`, 'i').test(part)) }))
}
