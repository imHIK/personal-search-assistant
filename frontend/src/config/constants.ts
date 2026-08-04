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
