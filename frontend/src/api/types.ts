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
  direction: CursorDirection
  status: CursorStatus
  retryCount: number
  lastError: string | null
  lastRunAt: string | null
  fetched: number
  position: Blob
}

// ---- Connections ---------------------------------------------------------------------------

export interface Connection {
  id: string
  name: string
  type: SourceType
  /** Returned **unredacted** by the backend — mask before rendering. */
  auth: Blob
  config: Blob
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
  makeDefault?: boolean
}

export interface PatchConnectionBody {
  name?: string
  auth?: Blob
  config?: Blob
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
}

export type CreateDigestBody = Pick<Digest, 'name'> &
  Partial<Omit<Digest, 'id' | 'name' | 'nextRunAt' | 'createdAt' | 'updatedAt'>>

export interface DigestRunItem {
  entityId: string
  chunkId: string
  title: string | null
  uri: string | null
  score: number
  snippet: string | null
}

export interface DigestRun {
  id: string
  digestId: string
  ranAt: string
  items: DigestRunItem[]
  /** The LLM task's reply, or null when the digest names no task. */
  taskOutput: string | null
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
