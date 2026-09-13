import type { SearchMode } from '@/api/types'

/**
 * Enumerations that drive their own controls. Nothing here is repeated inline in a component —
 * a new chunking strategy or search mode is added once, in this file.
 */

export const searchModes: { value: SearchMode; label: string; hint: string }[] = [
  {
    value: 'HYBRID',
    label: 'Balanced',
    hint: 'Matches both wording and meaning. Best for most searches.',
  },
  {
    value: 'LEXICAL',
    label: 'Exact words',
    hint: 'Only matches the words you typed. Good for names, codes and error strings.',
  },
  {
    value: 'SEMANTIC',
    label: 'By meaning',
    hint: 'Finds related ideas even when the wording differs.',
  },
]

export const DEFAULT_SEARCH_MODE: SearchMode = 'HYBRID'
export const DEFAULT_TOP_K = 10
export const TOP_K_OPTIONS = [5, 10, 20, 50]

/** Mirrors `app.chunking.strategy` in application.properties. */
export const chunkingStrategies = [
  { value: 'recursive', label: 'Recursive', hint: 'Splits on paragraphs, then lines, then words. The default.' },
  { value: 'character', label: 'Character', hint: 'Splits on a single separator you provide.' },
  { value: 'fixed-size', label: 'Fixed size', hint: 'Blind sliding window over characters.' },
  { value: 'token', label: 'Token', hint: 'Sizes chunks by real tokens rather than characters.' },
]

/**
 * How far back a digest run looks. Separate from the schedule: "run daily, but consider the last
 * week" is a normal thing to want, and an empty value means no time bound at all.
 */
/**
 * How far back a run looks. "No time limit" leads because it is the safe default: the window filters
 * on when a chunk was *indexed*, so a source that finishes ingesting and is then left alone drops out
 * of a short window and never returns — a digest set to "Last day" over a static folder is empty on
 * every run, forever. Newness is `onlyNew`'s job; the window is only an extra bound on top.
 */
export const digestWindows = [
  { value: '', label: 'No time limit' },
  { value: '1d', label: 'Last day' },
  { value: '7d', label: 'Last week' },
  { value: '30d', label: 'Last month' },
] as const

/**
 * Cadences offered for a digest. Deliberately not `schedulePresets`: a digest has no "only when I
 * ask" — that is what pausing it means — and hourly matters here in a way it does not for ingestion.
 */
export const digestIntervals = [
  { value: '1h', label: 'Every hour' },
  { value: '6h', label: 'Every 6 hours' },
  { value: '1d', label: 'Once a day' },
  { value: '7d', label: 'Once a week' },
] as const

/**
 * Map an interval the API returned back onto one of the options above.
 *
 * The backend stores a `Duration` and serialises it ISO-8601, so a digest created with "1d" reads
 * back as "PT24H". Without this the edit form's picker would match nothing, fall back to its first
 * option, and quietly rewrite the cadence on save.
 */
export function digestIntervalValue(interval: string | null): string {
  if (!interval) return '1d'
  const direct = digestIntervals.find((option) => option.value === interval)
  if (direct) return direct.value
  const hours = interval.match(/^PT(\d+)H$/)
  if (hours) {
    const n = Number(hours[1])
    if (n === 1) return '1h'
    if (n === 6) return '6h'
    if (n === 24) return '1d'
    if (n === 168) return '7d'
  }
  return interval
}

/** How far back a run looks, in words — "Last week" rather than "P7D". */
export function formatDigestWindow(window: string | null): string | null {
  if (!window) return null
  const direct = digestWindows.find((option) => option.value === window)
  if (direct) return direct.label
  const days = window.match(/^P(\d+)D$/)
  if (days) {
    const matched = digestWindows.find((option) => option.value === `${days[1]}d`)
    if (matched) return matched.label
  }
  const hours = window.match(/^PT(\d+)H$/)
  if (hours && Number(hours[1]) === 24) {
    return digestWindows.find((option) => option.value === '1d')?.label ?? window
  }
  return window
}

/** How often a digest runs, in words. Never the raw ISO duration, which reads as a machine error. */
export function formatDigestInterval(interval: string | null): string | null {
  if (!interval) return null
  const value = digestIntervalValue(interval)
  return digestIntervals.find((option) => option.value === value)?.label ?? interval
}

/**
 * Check-frequency presets, mapped onto the `interval`/`scheduleEnabled` pair the API takes.
 * `cron` is deliberately not offered here — it is a technical-details field.
 */
export const schedulePresets = [
  { value: 'manual', label: 'Only when I ask', interval: null, enabled: false },
  { value: '15m', label: 'Every 15 minutes', interval: '15m', enabled: true },
  { value: '1h', label: 'Every hour', interval: '1h', enabled: true },
  { value: '6h', label: 'Every 6 hours', interval: '6h', enabled: true },
  { value: '1d', label: 'Once a day', interval: '1d', enabled: true },
] as const

export type SchedulePresetValue = (typeof schedulePresets)[number]['value']

/** Map an existing schedule back onto a preset, so editing shows what is actually set. */
export function schedulePresetFor(
  interval: string | null,
  cron: string | null,
  enabled: boolean,
): SchedulePresetValue | 'custom' {
  if (cron) return 'custom'
  if (!enabled) return 'manual'
  const match = schedulePresets.find((p) => p.interval === interval)
  return match?.value ?? 'custom'
}

export const PAGE_SIZE = 25
export const PAGE_SIZE_OPTIONS = [25, 50, 100]

/** How often live views re-poll while work is in flight. Matches app.indexing.poll-interval. */
export const POLL_INTERVAL_MS = 5000
/** Health check cadence for the connection indicator. */
export const HEALTH_INTERVAL_MS = 15000
/** How long a mutation keeps its query polling, so async server work shows up on its own. */
export const POLL_AFTER_MUTATION_MS = 30000
/** How long a cited result stays ringed after a citation chip jumps to it. */
export const CITATION_HIGHLIGHT_MS = 1200
