import type {
  Channel,
  ChannelStatus,
  ConnectionStatus,
  CursorDirection,
  CursorInfo,
  CursorStatus,
  Delivery,
  DeliveryStatus,
  EntityItem,
  Knowledge,
} from '@/api/types'
import { relativeTime } from '@/lib/utils'

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
  | 'throttled'
  | 'attention'
  | 'removed'

const sourceStates: Record<SourceState, Presented> = {
  'setting-up': { label: 'Setting up', tone: 'busy', hint: 'Looking through the source for the first time' },
  importing: { label: 'Importing older items', tone: 'busy', hint: 'Working backwards through existing content' },
  processing: { label: 'Processing', tone: 'busy', hint: 'Reading and indexing what was imported' },
  'up-to-date': { label: 'Up to date', tone: 'ok', hint: 'Everything is searchable' },
  paused: { label: 'Paused', tone: 'wait', hint: 'Not checking for new items until you resume' },
  throttled: {
    label: 'Waiting on a rate limit',
    tone: 'wait',
    hint: 'The service is only letting us read so fast. This resumes on its own.',
  },
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

  // Throttled outranks "importing": a held stream is not making progress, and saying so is the
  // whole point of the state — the fix, if the wait is intolerable, is the user's (raise the
  // account's limit). Anything still running takes precedence, since that is real progress.
  const running = cursors?.some((c) => c.status === 'IN_PROGRESS')
  if (!running && cursors?.some((c) => c.status === 'RATE_LIMITED')) return 'throttled'

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

/**
 * `DELETED` is absent on purpose: the API's listing hides tombstoned entities unless a filter names
 * that status, so there is no state to show for one.
 *
 * A retry is not a state here either. An item that failed once and is waiting to be tried again is
 * genuinely queued, and the red warning icon the row already renders beside the badge is what says
 * something went wrong — two signals for one fact, rather than a "retrying" badge competing with it.
 */
export type ItemState = 'queued' | 'indexing' | 'indexed' | 'failed'

const itemStates: Record<ItemState, Presented> = {
  queued: { label: 'Queued', tone: 'busy', hint: 'Waiting to be read and indexed' },
  indexing: { label: 'Indexing', tone: 'busy', hint: 'Being read and indexed right now' },
  indexed: { label: 'Indexed', tone: 'ok', hint: 'Indexed and returned in results' },
  failed: { label: 'Failed', tone: 'alert', hint: 'This item could not be read' },
}

export function itemState(item: Pick<EntityItem, 'status' | 'needsReindex'>): ItemState {
  if (item.status === 'FAILED') return 'failed'
  if (item.status === 'INDEXING') return 'indexing'
  // A re-index is queued work against content that is still searchable in its previous form, so it
  // reads as queued rather than keeping an "indexed" badge that is about to stop being true.
  if (item.status === 'INDEXED') return item.needsReindex ? 'queued' : 'indexed'
  return 'queued'
}

export function presentItem(item: Pick<EntityItem, 'status' | 'needsReindex'>): Presented {
  const state = itemState(item)
  return { ...itemStates[state], raw: item.status ?? undefined }
}

// ---- Groups (Cursor) ------------------------------------------------------------------------

/**
 * Cursor status in plain language. Note the words "cursor", "lease" and "position" never appear —
 * a user is being told whether a stream of content is finished, waiting, or broken.
 */
const cursorStates: Record<CursorStatus, Presented> = {
  EXHAUSTED: { label: 'Synced', tone: 'ok', hint: 'Everything here has been imported' },
  IDLE: { label: 'Synced', tone: 'ok', hint: 'Up to date. Waiting for the next check.' },
  AVAILABLE: { label: 'Queued', tone: 'busy', hint: 'Will be picked up shortly' },
  IN_PROGRESS: { label: 'Syncing', tone: 'busy' },
  SUSPENDED: { label: 'Paused', tone: 'wait' },
  RATE_LIMITED: {
    label: 'Syncing',
    tone: 'busy',
    hint: 'The service is only letting us read so fast. This picks up again by itself.',
  },
  RETIRED: {
    label: 'Removed at source',
    tone: 'wait',
    hint: 'It disappeared at the source. What was already imported is kept.',
  },
  FAILED: { label: 'Failed', tone: 'alert', hint: 'Stopped after repeated errors' },
}

/**
 * Takes the cursor rather than the bare status because `RATE_LIMITED` has two readings. Nothing
 * writes the status back when a hold elapses — the backend's claim query simply stops excluding the
 * stream — so between the limit reopening and the next poll it is still `RATE_LIMITED` while
 * genuinely queued. Reading the instant is what keeps the badge honest in both directions.
 */
export function presentCursorStatus(cursor: Pick<CursorInfo, 'status' | 'nextAttemptAt'>): Presented {
  const { status, nextAttemptAt } = cursor
  if (status === 'RATE_LIMITED') {
    const until = nextAttemptAt ? new Date(nextAttemptAt).getTime() : null
    if (until === null || Number.isNaN(until) || until <= Date.now()) {
      return { ...cursorStates.AVAILABLE, raw: status }
    }
    const when = relativeTime(nextAttemptAt)
    return {
      ...cursorStates.RATE_LIMITED,
      hint: when ? `${cursorStates.RATE_LIMITED.hint} Next try ${when}.` : cursorStates.RATE_LIMITED.hint,
      raw: status,
    }
  }
  return cursorStates[status] ? { ...cursorStates[status], raw: status } : neutral(status)
}

const directionLabels: Record<CursorDirection, string> = {
  BACKWARD: 'Older items',
  FORWARD: 'New items',
}

export function presentDirection(direction: CursorDirection): string {
  return directionLabels[direction] ?? direction
}

/**
 * The one state shown for a group (an iterable), folded from its one or two cursors.
 *
 * A group is a folder, a label, a company — not a direction. Showing a line per direction meant
 * every forward-only source (every job board) repeated "New items" on every row, saying nothing,
 * while the fact a user wants — is this one moving, stuck, or done — was split across two badges.
 */
export type GroupState = 'failed' | 'syncing' | 'queued' | 'paused' | 'removed' | 'synced'

const groupStates: Record<GroupState, Presented> = {
  failed: { label: 'Failed', tone: 'alert', hint: 'Stopped after repeated errors' },
  syncing: { label: 'Syncing', tone: 'busy', hint: 'Reading from the source now' },
  queued: { label: 'Queued', tone: 'busy', hint: 'Will be picked up shortly' },
  paused: { label: 'Paused', tone: 'wait', hint: 'Not checking until the source is resumed' },
  removed: {
    label: 'Removed at source',
    tone: 'wait',
    hint: 'It disappeared at the source. What was already imported is kept.',
  },
  synced: { label: 'Synced', tone: 'ok', hint: 'Up to date. Waiting for the next check.' },
}

/** True while a rate-limit hold is still in the future — see {@link presentCursorStatus}. */
function onHold(cursor: CursorInfo): boolean {
  if (cursor.status !== 'RATE_LIMITED') return false
  const until = cursor.nextAttemptAt ? new Date(cursor.nextAttemptAt).getTime() : null
  return until !== null && !Number.isNaN(until) && until > Date.now()
}

/**
 * Worst-news-first, because a row is scanned for problems: anything broken outranks anything
 * running, and "synced" is only claimed when nothing else is true of either direction.
 */
export function groupState(cursors: CursorInfo[]): GroupState {
  const has = (status: CursorStatus) => cursors.some((cursor) => cursor.status === status)

  if (has('FAILED')) return 'failed'
  if (has('IN_PROGRESS') || cursors.some(onHold)) return 'syncing'
  if (has('AVAILABLE') || has('RATE_LIMITED')) return 'queued'
  if (has('SUSPENDED')) return 'paused'
  if (cursors.length > 0 && cursors.every((cursor) => cursor.status === 'RETIRED')) return 'removed'
  return 'synced'
}

export function presentGroup(cursors: CursorInfo[]): Presented {
  const state = groupState(cursors)
  return { ...groupStates[state], raw: cursors.map((cursor) => cursor.status).join(' / ') }
}

/**
 * The one-line reason a group is showing a warning, or null when it is not. Kept separate from the
 * badge so a rate-limited group can read "Syncing" and still carry the ⚠ that explains the wait —
 * the same pairing an item row uses for a retry.
 */
export function groupAlert(cursors: CursorInfo[]): string | null {
  const failed = cursors.find((cursor) => cursor.lastError);
  if (failed?.lastError) return failed.lastError
  const held = cursors.find(onHold)
  if (held) {
    const when = relativeTime(held.nextAttemptAt)
    return when
      ? `The service is only letting us read so fast. Next try ${when}.`
      : 'The service is only letting us read so fast.'
  }
  return null
}

/** History is still being walked — the only time a direction is worth naming on a row. */
export function isImportingHistory(cursors: CursorInfo[]): boolean {
  return cursors.some(
    (cursor) =>
      cursor.direction === 'BACKWARD' &&
      cursor.status !== 'EXHAUSTED' &&
      cursor.status !== 'RETIRED',
  )
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
  JOB_POSTING: 'Job posting',
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

/**
 * The name shown for a group, by iterable id. Both the Groups tab and each item row go through this,
 * so an item's "from" and the group's row can never name the same thing two ways. The backend
 * snapshots a display name on the cursor at discovery; older cursors have none, so the
 * id-shortening fallback stays.
 */
export function groupName(iterableId: string, cursors: CursorInfo[] | undefined, fallback: string): string {
  const named = cursors?.find((cursor) => cursor.iterableId === iterableId && cursor.iterableName)
  return named?.iterableName ?? presentIterableId(iterableId, fallback)
}

// ---- Publishing channel + delivery state ------------------------------------------------------

const channelStates: Record<ChannelStatus, Presented> = {
  ACTIVE: { label: 'Working', tone: 'ok' },
  ERROR: {
    label: 'Not delivering',
    tone: 'alert',
    hint: 'The last send was refused. Fix the problem, then send a test — queued messages wait until then.',
  },
}

/** Paused wins over status: a paused channel is not sending whatever its last result was. */
export function presentChannel(channel: Pick<Channel, 'status' | 'enabled'>): Presented {
  if (!channel.enabled) {
    return { label: 'Paused', tone: 'wait', hint: 'Messages stay queued until it is resumed.', raw: 'DISABLED' }
  }
  const status = channel.status
  return channelStates[status] ? { ...channelStates[status], raw: status } : neutral(status)
}

const deliveryStates: Record<DeliveryStatus, Presented> = {
  PENDING: { label: 'Queued', tone: 'busy', hint: 'Waiting to be sent.' },
  SENT: { label: 'Sent', tone: 'ok' },
  FAILED: {
    label: 'Not delivered',
    tone: 'alert',
    hint: 'It failed too many times in a row and was set aside. Retry it once the problem is fixed.',
  },
}

/** A queued delivery that has already failed is retrying, which reads differently from waiting. */
export function presentDelivery(delivery: Pick<Delivery, 'status' | 'attempts' | 'nextAttemptAt'>): Presented {
  const { status, attempts, nextAttemptAt } = delivery
  if (status === 'PENDING' && attempts > 0) {
    const when = relativeTime(nextAttemptAt)
    return {
      label: 'Retrying',
      tone: 'wait',
      hint: when ? `The last attempt failed. Next try ${when}.` : 'The last attempt failed.',
      raw: status,
    }
  }
  return deliveryStates[status] ? { ...deliveryStates[status], raw: status } : neutral(status)
}
