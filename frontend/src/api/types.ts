
export type SourceType =
  | 'LOCAL_FS'
  | 'GMAIL'
  | 'SLACK'
  | 'GOOGLE_DRIVE'
  | 'NOTION'
  | 'JOB_BOARDS'

export type KnowledgeStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ERROR' | 'DELETED'
export type EntityStatus = 'INGESTED' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'DELETED'
export type EntityType = 'FILE' | 'MESSAGE' | 'EMAIL' | 'PAGE' | 'JOB_POSTING' | 'OTHER'
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

export type Blob = Record<string, unknown>

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

/**
 * `period` null inherits (connector default, then server); unset at every tier means never expire.
 */
export interface RetentionSettings {
  period: string | null
}

export interface KnowledgeConfig {
  scheduleSettings: ScheduleSettings
  webhookSettings: WebhookSettings
  backfill: { enabled: boolean }
  chunking: ChunkingSettings
  retention: RetentionSettings
  /** A METADATA task run on each entity while it is indexed. */
  enrichment: { taskId: string | null }
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
  /** Fixed at creation: the boundary between backfill and incremental sync. */
  anchor: string
  /** null means "due now". */
  nextSyncDueAt: string | null
  status: KnowledgeStatus
  lastError: string | null
  stats: KnowledgeStats
  createdAt: string
  updatedAt: string
  syncGeneration: number
}

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
  /**
   * `Durations` shorthand such as `14d`. Null inherits; on PATCH an explicit null clears back to
   * inherit.
   */
  retentionPeriod?: string | null
  /** A METADATA task id; on PATCH an explicit null stops enriching. */
  enrichTaskId?: string | null
}

/**
 * Absent leaves a field unchanged, null clears it back to inherit (`cron`, `interval`, chunking,
 * `retentionPeriod`), a value sets it. `name`/`auth`/`inputs` reject null.
 */
export type PatchKnowledgeBody = Partial<
  Omit<CreateKnowledgeBody, 'type' | 'connectionId'> & {
    webhookEnabled: boolean
    webhookSecret: string
  }
>

export interface EntityItem {
  id: string
  iterableId: string | null
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
  createdAt: string
  /** Moves with indexing bookkeeping, so it is not a content date. The listing sorts on it. */
  updatedAt: string
  /** The metadata task failed on it; the entity is indexed without fresh values. */
  enrichmentError?: string | null
}

export interface EntityPage {
  items: EntityItem[]
  total: number
  limit: number
  offset: number
}

export interface CursorInfo {
  id: string
  iterableId: string
  /** Null on cursors created before names were stored. */
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

/** At most `permits` requests in any `windowSeconds`; a call must satisfy every rule. */
export interface RateLimitRule {
  permits: number
  windowSeconds: number
}

/** Empty `rules` means no limit. */
export interface RateLimitPolicy {
  rules: RateLimitRule[]
}

export interface Connection {
  id: string
  name: string
  type: string
  /** Returned **unredacted** by the backend — mask before rendering. */
  auth: Blob
  config: Blob
  /** Null when the account uses the server-wide default. */
  rateLimit: RateLimitPolicy | null
  status: ConnectionStatus
  lastError: string | null
  createdAt: string
  updatedAt: string
  /** Jackson emits this under either key; read it through `isDefaultConnection()`. */
  isDefault?: boolean
  default?: boolean
}

export interface CreateConnectionBody {
  name: string
  type: string
  auth?: Blob
  config?: Blob
  rateLimit?: RateLimitPolicy
  makeDefault?: boolean
}

export interface PatchConnectionBody {
  name?: string
  auth?: Blob
  config?: Blob
  /** Absent leaves the limit alone; `{ rules: [] }` removes it. */
  rateLimit?: RateLimitPolicy
}

export interface SearchBody {
  query: string
  knowledgeIds?: string[]
  /**
   * Keyed by index field path. A scalar is an exact term match; a `{ gte, lte }` map is a range.
   */
  filters?: Blob
  topK?: number
  mode?: SearchMode
  answer?: boolean
  /** Matching chunks per result, the best included; 1 drops the further matches. */
  maxChunksPerEntity?: number
  collapseDuplicates?: boolean
}

export interface SearchMatch {
  chunkId: string
  ordinal: number
  snippet: string | null
  score: number
}

export interface SearchRanking {
  /** Null when the lexical leg did not return the chunk. */
  lexicalRank: number | null
  /** Null when the vector leg did not return the chunk. */
  vectorRank: number | null
  retrievalScore: number
  groupedScore: number
  /** Freshness multiplier; 1 when none applied. */
  recencyFactor: number
}

export interface SearchHit {
  /** `<entityId>_<ordinal>` of the best-matching chunk. */
  chunkId: string
  entityId: string
  knowledgeId: string
  ordinal: number
  title: string | null
  snippet: string | null
  uri: string | null
  score: number
  metadata: Blob
  moreMatches: SearchMatch[]
  ranking: SearchRanking
}

export interface SearchResult {
  hits: SearchHit[]
  /** Non-null only when the request set `answer: true`. Cites hits as `[n]`, 1-based. */
  answer: string | null
  /** Set when `answer: true` got no answer (LLM unavailable); the hits are still valid. */
  answerError: string | null
  /** Set when the query could not be embedded; the search ran keyword-only. */
  vectorError: string | null
  tookMs: number
}

export interface SyncTrigger {
  knowledgeId: string
  cursorsArmed: number
}

export interface Digest {
  id: string
  name: string
  query: string | null
  knowledgeIds: string[]
  filters: Blob
  /** e.g. "1d"; null means no time bound. */
  window: string | null
  cron: string | null
  interval: string | null
  taskId: string | null
  /** False skips the task without forgetting it. */
  useLlm: boolean
  topK: number
  collapseDuplicates: boolean
  maxChunksPerEntity: number | null
  onlyNew: boolean
  enabled: boolean
  nextRunAt: string | null
  createdAt: string | null
  updatedAt: string | null
  /** Set only by the reset action, never by an edit. */
  historyResetAt: string | null
  /** Each run that finds something new, or fails, is sent to these; empty sends nowhere. */
  channelIds: string[]
}

export type CreateDigestBody = Pick<Digest, 'name'> &
  Partial<Omit<Digest, 'id' | 'name' | 'nextRunAt' | 'createdAt' | 'updatedAt' | 'historyResetAt'>>

/** Absent fields are left alone. */
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
   * Keys come from the task, so no component may branch on them; `config/annotations.ts` presents
   * them.
   */
  annotations: Record<string, string | number | boolean>
}

export interface DigestRun {
  id: string
  digestId: string
  ranAt: string
  items: DigestRunItem[]
  taskOutput: string | null
  /** Results the search returned before already-seen ones were dropped. */
  candidates: number
  /** How many of those were dropped as already reported. */
  suppressed: number
  /**
   * What the search finds without the look-back window; counted only when the windowed search found
   * nothing.
   */
  outsideWindow: number
  error: string | null
  /** The task failed while the search succeeded; the items are kept. */
  taskError: string | null
}

export interface CompanyLookup {
  company: string
  /** Null when no supported platform hosts a board — a normal answer, not a failure. */
  platform: string | null
  handle: string | null
  /** Postings on that board, before any location filter. */
  postings: number
  found: boolean
}

export type TaskMode = 'SIMPLE' | 'RAW'

export type TaskOutput = 'SUMMARY' | 'PER_ITEM' | 'METADATA'

export type TaskSourceText = 'CHUNK' | 'ENTITY'

/** BOOLEAN and LIST are for METADATA tasks only. */
export type TaskFieldType = 'NUMBER' | 'TEXT' | 'BOOLEAN' | 'LIST'

export interface TaskField {
  name: string
  type: TaskFieldType
  description: string
  /** When true the model may answer null, and the field is then simply not shown. */
  optional: boolean
  /** TEXT and LIST only: the allowed values; a reply outside them is dropped. */
  values?: string[]
}

export interface Task {
  id: string
  name: string
  description: string
  builtIn: boolean
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

export type ChannelType = 'EMAIL' | 'SLACK' | 'WHATSAPP'
export type ChannelStatus = 'ACTIVE' | 'ERROR'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'FAILED'

export interface Channel {
  id: string
  name: string
  type: ChannelType
  /** The account it sends through; null means the default account of that type. */
  connectionId: string | null
  /** Publisher-defined destination. EMAIL: `{ to: string[], cc?: string[], subjectPrefix?: string }`. */
  target: Blob
  /** A paused channel keeps its messages queued rather than failing them. */
  enabled: boolean
  /** ERROR = a send was refused permanently; queued messages wait until a test succeeds. */
  status: ChannelStatus
  lastError: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface CreateChannelBody {
  name: string
  type: ChannelType
  connectionId?: string
  target: Blob
  enabled?: boolean
}

/** Absent fields are unchanged. `target` replaces the whole target. */
export interface PatchChannelBody {
  name?: string
  /** `''` switches back to the default account; absent leaves it unchanged. */
  connectionId?: string
  target?: Blob
  enabled?: boolean
}

export interface PublishMessageItem {
  title: string | null
  uri: string | null
  text: string | null
  fields: Record<string, string | number | boolean>
}

export interface PublishMessage {
  title: string | null
  intro: string | null
  items: PublishMessageItem[]
  link: string | null
}

export interface Delivery {
  id: string
  channelId: string
  origin: { kind: string; refId: string | null }
  dedupeKey: string | null
  message: PublishMessage
  status: DeliveryStatus
  /** Consecutive failed attempts; reset by a send or a retry. */
  attempts: number
  nextAttemptAt: string | null
  leasedUntil: string | null
  lastError: string | null
  providerMessageId: string | null
  createdAt: string
  sentAt: string | null
}
