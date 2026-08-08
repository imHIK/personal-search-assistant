import type {
  ConnectionStatus,
  CursorDirection,
  CursorInfo,
  CursorStatus,
  EntityItem,
  EntityStatus,
  Knowledge,
} from '@/api/types'

/**
 * Translation from the backend's internal state machines to what a user is shown.
 *
 * The domain has five status enums, two cursor directions, leases, checksums and generations.
 * A user needs none of that — they need to know whether their stuff is searchable yet, and
 * whether anything needs their attention. Everything in this file is a lookup table with a safe
 * fallback, never a `switch` with exhaustive cases: the backend can add an enum constant and the
 * worst that happens is a neutral badge showing the raw name.
 */

/** The only four colours in the app. A user learns them once and they mean the same everywhere. */
export type Tone = 'ok' | 'busy' | 'wait' | 'alert' | 'neutral'

export interface Presented {
  /** What the user reads. */
  label: string
  tone: Tone
  /** One sentence of plain-English explanation, shown as a subtitle or tooltip. */
  hint?: string
  /** The raw enum name, surfaced only when Technical details is on. */
  raw?: string
}

const neutral = (raw: string): Presented => ({ label: raw, tone: 'neutral', raw })

// ---- Source (Knowledge) state ---------------------------------------------------------------

/**
 * The single derived state shown for a source. This intentionally folds together three separate
 * backend facts — `status`, the cursor states, and the entity counters — because "is my stuff
 * searchable?" cannot be answered by any one of them alone.
 */
export type SourceState =
  | 'setting-up'
  | 'importing'
  | 'processing'
  | 'up-to-date'
  | 'paused'
  | 'attention'
  | 'removed'

const sourceStates: Record<SourceState, Presented> = {
  'setting-up': { label: 'Setting up', tone: 'busy', hint: 'Looking through the source for the first time' },
  importing: { label: 'Importing older items', tone: 'busy', hint: 'Working backwards through existing content' },
  processing: { label: 'Processing', tone: 'busy', hint: 'Reading and indexing what was imported' },
  'up-to-date': { label: 'Up to date', tone: 'ok', hint: 'Everything is searchable' },
  paused: { label: 'Paused', tone: 'wait', hint: 'Not checking for new items until you resume' },
  attention: { label: 'Needs attention', tone: 'alert', hint: 'Something stopped working' },
  removed: { label: 'Removed', tone: 'neutral' },
}

/**
 * Derive the state to show. `cursors` is optional — the list view doesn't fetch them, so without
 * them "importing" collapses into "processing", which is the honest reading of what's known.
 */
export function sourceState(knowledge: Knowledge, cursors?: CursorInfo[]): SourceState {
  const { status, stats } = knowledge

  if (status === 'DELETED') return 'removed'
  if (status === 'ERROR') return 'attention'
  if (status === 'PAUSED') return 'paused'
  if (stats.failed > 0) return 'attention'
  if (status === 'DRAFT') return 'setting-up'

  const pending = stats.entities - stats.indexed - stats.failed

  // A backward cursor that hasn't EXHAUSTED means history is still being walked. That is a
  // different, and much longer, wait than "a few items are still being indexed" — worth its own
  // state so a big first import doesn't look stuck.
  const stillImporting = cursors?.some(
    (c) => c.direction === 'BACKWARD' && c.status !== 'EXHAUSTED' && c.status !== 'RETIRED',
  )
  if (stillImporting) return 'importing'

  if (stats.entities === 0) return 'setting-up'
  if (pending > 0) return 'processing'
  return 'up-to-date'
}

export function presentSource(knowledge: Knowledge, cursors?: CursorInfo[]): Presented {
  const state = sourceState(knowledge, cursors)
  return { ...sourceStates[state], raw: knowledge.status }
}

// ---- Item (Entity) state --------------------------------------------------------------------

/** `DELETED` is absent on purpose: removed items are not listed rather than shown as a state. */
export type ItemState = 'searchable' | 'processing' | 'failed'

const itemStates: Record<ItemState, Presented> = {
  searchable: { label: 'Searchable', tone: 'ok', hint: 'Indexed and returned in results' },
  processing: { label: 'Processing', tone: 'busy', hint: 'Waiting to be read and indexed' },
  failed: { label: "Couldn't process", tone: 'alert', hint: 'This item could not be read' },
}

export function itemState(item: Pick<EntityItem, 'status' | 'needsReindex'>): ItemState {
  if (item.status === 'FAILED') return 'failed'
  if (item.status === 'INDEXED') return item.needsReindex ? 'processing' : 'searchable'
  return 'processing'
}

export function presentItem(item: Pick<EntityItem, 'status' | 'needsReindex'>): Presented {
  const state = itemState(item)
  return { ...itemStates[state], raw: item.status ?? undefined }
}

/** Maps the UI's filter tabs onto the `status` query param the API actually accepts. */
export const itemFilters: { id: string; label: string; status: EntityStatus | null }[] = [
  { id: 'all', label: 'All', status: null },
  { id: 'searchable', label: 'Searchable', status: 'INDEXED' },
  { id: 'processing', label: 'Processing', status: 'INGESTED' },
  { id: 'failed', label: "Couldn't process", status: 'FAILED' },
]

// ---- Sync activity (Cursor) -----------------------------------------------------------------

/**
 * Cursor status in plain language. Note the words "cursor", "lease" and "position" never appear —
 * a user is being told whether a stream of content is finished, waiting, or broken.
 */
const cursorStates: Record<CursorStatus, Presented> = {
  EXHAUSTED: { label: 'Complete', tone: 'ok', hint: 'Everything here has been imported' },
  IDLE: { label: 'Waiting for the next check', tone: 'wait' },
  AVAILABLE: { label: 'Queued', tone: 'busy', hint: 'Will be picked up shortly' },
  IN_PROGRESS: { label: 'Importing now', tone: 'busy' },
  SUSPENDED: { label: 'Paused', tone: 'wait' },
  RETIRED: {
    label: 'No longer in this source',
    tone: 'wait',
    hint: 'It disappeared at the source. What was already imported is kept.',
  },
  FAILED: { label: 'Stopped after repeated errors', tone: 'alert' },
}

export function presentCursorStatus(status: CursorStatus): Presented {
  return cursorStates[status] ? { ...cursorStates[status], raw: status } : neutral(status)
}

const directionLabels: Record<CursorDirection, string> = {
  BACKWARD: 'Older items',
  FORWARD: 'New items',
}

export function presentDirection(direction: CursorDirection): string {
  return directionLabels[direction] ?? direction
}

// ---- Account (Connection) state ---------------------------------------------------------------

const connectionStates: Record<ConnectionStatus, Presented> = {
  ACTIVE: { label: 'Connected', tone: 'ok' },
  ERROR: { label: 'Needs reconnecting', tone: 'alert', hint: 'The sign-in was rejected' },
  DISABLED: { label: 'Disabled', tone: 'wait' },
}

export function presentConnection(status: ConnectionStatus): Presented {
  return connectionStates[status] ? { ...connectionStates[status], raw: status } : neutral(status)
}

// ---- Item kind --------------------------------------------------------------------------------

const entityTypeLabels: Record<string, string> = {
  FILE: 'File',
  MESSAGE: 'Message',
  EMAIL: 'Email',
  PAGE: 'Page',
  OTHER: 'Other',
}

export function presentEntityType(type: string | null | undefined): string {
  if (!type) return '—'
  return entityTypeLabels[type] ?? type
}

/**
 * A readable name for an iterable. Connectors encode structure into the id (`folder:/path`,
 * `label:INBOX`, `root`), so this unpacks the common shapes and otherwise falls back to the id.
 */
export function presentIterableId(iterableId: string, fallback: string): string {
  if (!iterableId || iterableId === 'root' || iterableId === 'all') return fallback
  const separator = iterableId.indexOf(':')
  if (separator > 0) {
    const value = iterableId.slice(separator + 1)
    if (value) return value.split('/').filter(Boolean).pop() ?? value
  }
  return iterableId
}
