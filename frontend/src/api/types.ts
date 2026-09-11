/**
 * Wire types, mirroring the backend DTOs one-for-one. Hand-written rather than generated: the
 * surface is ~15 endpoints and the hand-written version can carry the notes that matter
 * (which fields lie, which are optional in practice).
 *
 * Field names here MUST match the Java records exactly — see src/main/java/io/personalassistant/
 * api/dto and domain/model.
 */

export type SourceType =
  | 'LOCAL_FS'
  | 'GMAIL'
  | 'SLACK'
  | 'GOOGLE_DRIVE'
  | 'NOTION'
  | 'JOB_BOARDS'

export type KnowledgeStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ERROR' | 'DELETED'
export type EntityStatus = 'INGESTED' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'DELETED'
export type EntityType = 'FILE' | 'MESSAGE' | 'EMAIL' | 'PAGE' | 'OTHER'
export type ConnectionStatus = 'ACTIVE' | 'ERROR' | 'DISABLED'
export type CursorDirection = 'BACKWARD' | 'FORWARD'
export type CursorStatus =
  | 'AVAILABLE'
  | 'IN_PROGRESS'
  | 'IDLE'
  | 'SUSPENDED'
  | 'EXHAUSTED'
  | 'RETIRED'
  | 'RATE_LIMITED'
  | 'FAILED'
export type SearchMode = 'LEXICAL' | 'SEMANTIC' | 'HYBRID'

/** Free-form blobs the backend never inspects (`inputs`, `auth`, `config`, `metadata`). */
export type Blob = Record<string, unknown>

// ---- Knowledge -----------------------------------------------------------------------------

export interface ScheduleSettings {
  cron: string | null
  interval: string | null
  enabled: boolean
}

export interface WebhookSettings {
  enabled: boolean
  secret: string | null
}

export interface ChunkingSettings {
  strategy: string | null
  maxSize: number | null
  overlap: number | null
  separators: string[]
}

export interface KnowledgeConfig {
  scheduleSettings: ScheduleSettings
  webhookSettings: WebhookSettings
  backfill: { enabled: boolean }
  chunking: ChunkingSettings
}

export interface ConnectorDetails {
  type: SourceType
  connectionId: string | null
  auth: Blob
}

export interface KnowledgeStats {
  entities: number
  indexed: number
  failed: number
}

export interface Knowledge {
  id: string
  name: string
  connectorDetails: ConnectorDetails
  inputs: Blob
  config: KnowledgeConfig
  /** Creation instant. Fixed forever — the boundary between backfill and incremental sync. */
  anchor: string
  /** null means "due now". */
  nextSyncDueAt: string | null
  status: KnowledgeStatus
  lastError: string | null
  /** Recomputed live on every read, so never stale. */
  stats: KnowledgeStats
  createdAt: string
  updatedAt: string
  syncGeneration: number
}

/**
 * Body for `POST /api/knowledge`. Flat on purpose — the backend assembles the nested Config.
 *
 * Note the response is **200 with `status: "ERROR"`** when verification fails, not a 4xx.
 */
export interface CreateKnowledgeBody {
  name: string
  type: SourceType
  connectionId?: string | null
  auth?: Blob
  inputs?: Blob
  cron?: string | null
  interval?: string | null
  scheduleEnabled?: boolean
  backfillEnabled?: boolean
  chunkingStrategy?: string | null
  chunkingMaxSize?: number | null
  chunkingOverlap?: number | null
  chunkingSeparators?: string[] | null
}

/**
 * Body for `PATCH /api/knowledge/{id}`. Every field is optional, and **absent and explicit null
 * both mean "unchanged"** — there is no way to clear `cron`/`interval` back to inherited.
 */
export type PatchKnowledgeBody = Partial<
  Omit<CreateKnowledgeBody, 'type' | 'connectionId'> & {
    webhookEnabled: boolean
    webhookSecret: string
  }
>

// ---- Entities ------------------------------------------------------------------------------

export interface EntityItem {
  id: string
  externalId: string
  entityType: EntityType | null
  status: EntityStatus | null
  title: string | null
  uri: string | null
  checksum: string | null
  chunkCount: number
  embeddingModel: string | null
  indexedAt: string | null
  error: string | null
  retryCount: number
  needsReindex: boolean
  /** First ingested, and immutable — this is the item's "added" date. */
  createdAt: string
  /**
   * Last write to the row from either stage, and the listing's sort key. Almost all of its movement is
   * indexing bookkeeping — a claim, a retry, a terminal write — so it is not a content date and belongs
   * behind the technical toggle rather than in the item's summary line.
   */
  updatedAt: string
}

export interface EntityPage {
  items: EntityItem[]
  /** Matches across all pages, not the page length. */
  total: number
  limit: number
  offset: number
}

// ---- Cursors -------------------------------------------------------------------------------

export interface CursorInfo {
  id: string
  iterableId: string
  /** Human label for the stream (folder, label, company). Null on cursors created before names were stored. */
  iterableName: string | null
  direction: CursorDirection
  status: CursorStatus
  retryCount: number
  lastError: string | null
  /** Set only while `status` is `RATE_LIMITED`: when the source's quota lets this stream run again. */
  nextAttemptAt: string | null
  lastRunAt: string | null
  fetched: number
  position: Blob
}

// ---- Connections ---------------------------------------------------------------------------

/**
 * One ceiling on how fast this app may call the account's service: at most `permits` requests in
 * any `windowSeconds`. Several compose, and a call must satisfy all of them.
 */
export interface RateLimitRule {
  permits: number
  windowSeconds: number
}

/** An empty `rules` array means no limit — and is how a limit is *removed* (see PatchConnectionBody). */
export interface RateLimitPolicy {
  rules: RateLimitRule[]
}

export interface Connection {
  id: string
  name: string
  type: SourceType
  /** Returned **unredacted** by the backend — mask before rendering. */
  auth: Blob
  config: Blob
  /** Null when the account uses the server-wide default. */
  rateLimit: RateLimitPolicy | null
  status: ConnectionStatus
  lastError: string | null
  createdAt: string
  updatedAt: string
  /**
   * Jackson serialises the `boolean isDefault` record component under one of these two keys
   * depending on whether it treats the accessor as a getter. Both are declared so neither
   * spelling breaks the UI; read it through `isDefaultConnection()` in ./connections.
   */
  isDefault?: boolean
  default?: boolean
}

export interface CreateConnectionBody {
  name: string
  type: SourceType
  auth?: Blob
  config?: Blob
  rateLimit?: RateLimitPolicy
  makeDefault?: boolean
}

export interface PatchConnectionBody {
  name?: string
  auth?: Blob
  config?: Blob
  /**
   * Absent leaves the existing limit alone, since that is what an omitted PATCH field means
   * everywhere else. To *remove* a limit send `{ rules: [] }` — there is no other way to say it.
   */
  rateLimit?: RateLimitPolicy
}

// ---- Search --------------------------------------------------------------------------------

export interface SearchBody {
  query: string
  knowledgeIds?: string[]
  /**
   * Filters keyed by full index field path (`sourceType`, `metadata.author`…). A scalar is an exact
   * term match; a `{ gte, lte }` map becomes a range, which is the only way to express a date window
   * or a numeric floor.
   */
  filters?: Blob
  topK?: number
  mode?: SearchMode
  answer?: boolean
  /** Cap on how many chunks one entity may contribute. 1 gives one result per document. */
  maxChunksPerEntity?: number
  /** Group near-identical results and keep one of each. Off by default on the server. */
  collapseDuplicates?: boolean
  /**
   * Search *by* an already-ingested entity rather than by typed text. When set, `query` stops being
   * the search text and becomes a statement of intent steering how the document is decomposed; it
   * may be blank.
   */
  sourceEntityId?: string
}

export interface SearchHit {
  /** `<entityId>_<ordinal>` — pins the matched passage. */
  chunkId: string
  entityId: string
  /** What lets a result be attributed to the source it came from. */
  knowledgeId: string
  /** Position of the chunk inside its entity — orders several hits from one document. */
  ordinal: number
  title: string | null
  /** Display excerpt: the matching region when the lexical leg produced a highlight, else the head. */
  snippet: string | null
  uri: string | null
  score: number
  metadata: Blob
}

export interface SearchResult {
  hits: SearchHit[]
  /** Non-null only when the request set `answer: true`. Cites hits as `[n]`, 1-based. */
  answer: string | null
  /**
   * Why no answer came back despite `answer: true` — an unavailable or misconfigured LLM. The
   * server now returns 200 with the hits intact and this set, instead of 500ing and losing them,
   * so this is the primary signal; `useSearch`'s retry is only a fallback for older behaviour.
   */
  answerError: string | null
  tookMs: number
}

// ---- Indexing ------------------------------------------------------------------------------

export interface SyncTrigger {
  knowledgeId: string
  cursorsArmed: number
}

// ---- Digests --------------------------------------------------------------------------------

/**
 * A saved search that runs on a schedule and keeps its results. The job-hunt case (new postings
 * scored against a CV) is one row of this, not a separate feature.
 */
export interface Digest {
  id: string
  name: string
  /** With sourceEntityId set this is a statement of intent rather than the search text. */
  query: string | null
  /** Search *by* this entity instead of by typed text. */
  sourceEntityId: string | null
  knowledgeIds: string[]
  filters: Blob
  /** How far back a run looks, e.g. "1d". Null means no time bound. */
  window: string | null
  cron: string | null
  interval: string | null
  /** A prompt-catalogue task run over the results, or null for results only. */
  taskId: string | null
  topK: number
  collapseDuplicates: boolean
  maxChunksPerEntity: number | null
  /** Drop results an earlier run already reported — what makes it a digest. */
  onlyNew: boolean
  enabled: boolean
  nextRunAt: string | null
  createdAt: string | null
  updatedAt: string | null
  /**
   * When the already-seen set was last cleared. Read-only — set by the reset action, not by an edit,
   * so changing a digest never silently makes it re-report its whole window.
   */
  historyResetAt: string | null
}

export type CreateDigestBody = Pick<Digest, 'name'> &
  Partial<Omit<Digest, 'id' | 'name' | 'nextRunAt' | 'createdAt' | 'updatedAt' | 'historyResetAt'>>

/** Every field is optional: absent means "leave alone", which is what keeps an edit non-destructive. */
export type PatchDigestBody = Partial<
  Omit<Digest, 'id' | 'nextRunAt' | 'createdAt' | 'updatedAt' | 'historyResetAt'>
>

export interface DigestRunItem {
  entityId: string
  chunkId: string
  title: string | null
  uri: string | null
  score: number
  snippet: string | null
  /**
   * What the digest's task said about this item, keyed by whatever the task asked the model to
   * record. Open on purpose — the keys come from user-written tasks, so no component may branch on
   * them; `config/annotations.ts` decides how a key is presented.
   */
  annotations: Record<string, string | number | boolean>
}

export interface DigestRun {
  id: string
  digestId: string
  ranAt: string
  items: DigestRunItem[]
  /** The LLM task's reply, verbatim, or null when the digest names no task. */
  taskOutput: string | null
  /** Results the search returned before already-seen ones were dropped. */
  candidates: number
  /**
   * How many of those were dropped as already reported. With `candidates` this separates "nothing
   * matched" from "everything matched was already seen" — two very different empty runs.
   */
  suppressed: number
  /**
   * What the same search finds with the look-back window removed, counted only when the windowed
   * search found nothing. The window filters on when a chunk was *indexed*, so a source ingested
   * once and then left alone falls out of a short window and stays out — this is what separates
   * "widen the look-back" from "your query matches nothing".
   */
  outsideWindow: number
  /** Why the run failed. A failed run is still recorded, so a broken digest is visible. */
  error: string | null
}

/** What a candidate company name resolves to, before it is committed to a knowledge. */
export interface CompanyLookup {
  company: string
  /** Null when no supported platform hosts a board — a normal answer, not a failure. */
  platform: string | null
  handle: string | null
  /** Postings on that board, before any location filter. */
  postings: number
  found: boolean
}

// ---- Tasks ----------------------------------------------------------------------------------

/** How a task's prompt is put together. */
export type TaskMode = 'SIMPLE' | 'RAW'

/** Whether a task replies with one summary of the batch or a note on each result. */
export type TaskOutput = 'SUMMARY' | 'PER_ITEM'

/** Whether the model judges the matching passage or the whole document. */
export type TaskSourceText = 'CHUNK' | 'ENTITY'

export type TaskFieldType = 'NUMBER' | 'TEXT'

/** One thing a PER_ITEM task records about each result. Becomes an annotation on the run item. */
export interface TaskField {
  name: string
  type: TaskFieldType
  description: string
  /** When true the model may answer null, and the field is then simply not shown. */
  optional: boolean
}

/**
 * A row of the task library. Built-in tasks ship inside the app and are read-only — two of them are
 * what search itself runs — so the console offers Duplicate rather than Edit for those.
 */
export interface Task {
  id: string
  name: string
  description: string
  builtIn: boolean
  /** What in the app depends on this task, e.g. ["search"]. Empty for a task only digests use. */
  usedBy: string[]
  usableInDigest: boolean
  /** Null on a built-in task: its prompt is not exposed as editable fields. */
  mode: TaskMode | null
  instruction: string | null
  output: TaskOutput | null
  fields: TaskField[] | null
  system: string | null
  user: string | null
  llmProfile: string | null
  sourceText: TaskSourceText | null
  contextChars: number | null
  maxSources: number | null
  createdAt: string | null
  updatedAt: string | null
}

export type TaskBody = Partial<
  Omit<Task, 'id' | 'builtIn' | 'usedBy' | 'usableInDigest' | 'createdAt' | 'updatedAt'>
> &
  Pick<Task, 'name'>

/** The thin entity read used to show a document's title where only its id is held. */
export interface EntitySummary {
  id: string
  knowledgeId: string
  title: string | null
  uri: string | null
  status: string | null
}
