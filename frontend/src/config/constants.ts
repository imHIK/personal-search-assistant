import type { SearchMode } from '@/api/types'

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
/** 0 means every match; choosing none leaves the server default in force. */
export const MATCHES_PER_RESULT_OPTIONS = [1, 3, 5, 0]

/** Mirrors `app.chunking.strategy` in application.properties. */
export const chunkingStrategies = [
  { value: 'recursive', label: 'Recursive', hint: 'Splits on paragraphs, then lines, then words. The default.' },
  { value: 'character', label: 'Character', hint: 'Splits on a single separator you provide.' },
  { value: 'fixed-size', label: 'Fixed size', hint: 'Blind sliding window over characters.' },
  { value: 'token', label: 'Token', hint: 'Sizes chunks by real tokens rather than characters.' },
]

export const digestWindows = [
  { value: '', label: 'No time limit' },
  { value: '1d', label: 'Last day' },
  { value: '7d', label: 'Last week' },
  { value: '30d', label: 'Last month' },
] as const

/** Not `schedulePresets`: a digest has no "only when I ask" (that is pausing it). */
export const digestIntervals = [
  { value: '1h', label: 'Every hour' },
  { value: '6h', label: 'Every 6 hours' },
  { value: '1d', label: 'Once a day' },
  { value: '7d', label: 'Once a week' },
] as const

/**
 * The backend returns ISO-8601, so "1d" reads back as "PT24H"; unmatched, the picker would fall
 * back to its first option and rewrite the cadence on save.
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

export function formatDigestInterval(interval: string | null): string | null {
  if (!interval) return null
  const value = digestIntervalValue(interval)
  return digestIntervals.find((option) => option.value === value)?.label ?? interval
}

export const retentionUnits = [
  { value: 'm', label: 'Minutes' },
  { value: 'h', label: 'Hours' },
  { value: 'd', label: 'Days' },
] as const

export type RetentionUnit = (typeof retentionUnits)[number]['value']

const unitSeconds: Record<RetentionUnit, number> = { m: 60, h: 3600, d: 86400 }

/**
 * Accepts `Durations` shorthand (`14d`) or ISO-8601 (`P14D`, `PT36H`). Null for anything it cannot
 * represent.
 */
export function parseDuration(value: string | null | undefined): { amount: number; unit: RetentionUnit } | null {
  if (!value) return null
  let seconds: number | null = null
  const shorthand = value.trim().match(/^(\d+)\s*(ms|s|m|h|d)$/i)
  if (shorthand) {
    const n = Number(shorthand[1])
    const factor: Record<string, number> = { ms: 0.001, s: 1, m: 60, h: 3600, d: 86400 }
    seconds = n * factor[shorthand[2].toLowerCase()]
  } else {
    const iso = value.trim().match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/i)
    if (iso) {
      const [, d, h, m, s] = iso.map((part) => Number(part ?? 0))
      seconds = d * 86400 + h * 3600 + m * 60 + s
    }
  }
  if (!seconds || seconds < 60) return null
  for (const unit of ['d', 'h', 'm'] as const) {
    if (seconds % unitSeconds[unit] === 0) return { amount: seconds / unitSeconds[unit], unit }
  }
  return { amount: Math.round(seconds / 60), unit: 'm' }
}

export function normalizeDuration(value: string | null | undefined): string {
  const parsed = parseDuration(value)
  return parsed ? `${parsed.amount}${parsed.unit}` : ''
}

/** `cron` is deliberately not offered here: it is a technical-details field. */
export const schedulePresets = [
  { value: 'manual', label: 'Only when I ask', interval: null, enabled: false },
  { value: '15m', label: 'Every 15 minutes', interval: '15m', enabled: true },
  { value: '1h', label: 'Every hour', interval: '1h', enabled: true },
  { value: '6h', label: 'Every 6 hours', interval: '6h', enabled: true },
  { value: '1d', label: 'Once a day', interval: '1d', enabled: true },
] as const

export type SchedulePresetValue = (typeof schedulePresets)[number]['value']

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

/** Matches app.indexing.poll-interval. */
export const POLL_INTERVAL_MS = 5000
export const HEALTH_INTERVAL_MS = 15000
export const POLL_AFTER_MUTATION_MS = 30000
export const CITATION_HIGHLIGHT_MS = 1200
