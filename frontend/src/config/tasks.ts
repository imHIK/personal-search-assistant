import type { TaskFieldType, TaskOutput, TaskSourceText } from '@/api/types'

/** `fields`: the output is JSON shaped by the fields; `metadata`: it runs at indexing time, not in a digest. */
export const taskOutputs: {
  value: TaskOutput
  label: string
  hint: string
  fields: boolean
  metadata?: boolean
}[] = [
  {
    value: 'SUMMARY',
    label: 'One summary',
    hint: 'A single piece of writing about everything the digest found.',
    fields: false,
  },
  {
    value: 'PER_ITEM',
    label: 'Notes on each result',
    hint: 'A short note on every result, shown beside it. Choose this to get scores you can scan.',
    fields: true,
  },
  {
    value: 'METADATA',
    label: 'Metadata',
    hint: 'Extracts the fields from each item while it is indexed. Pick the task in a source’s settings.',
    fields: true,
    metadata: true,
  },
]

export function taskOutputFor(output: TaskOutput) {
  return taskOutputs.find((o) => o.value === output) ?? taskOutputs[0]
}

/** `values`: takes a list of allowed values; `metadataOnly`: offered on METADATA tasks only. */
export const taskFieldTypes: {
  value: TaskFieldType
  label: string
  hint: string
  values?: boolean
  metadataOnly?: boolean
}[] = [
  { value: 'NUMBER', label: 'Number', hint: 'Shown as a badge on the result. Use 0–10 for scores.' },
  {
    value: 'TEXT',
    label: 'Text',
    hint: 'Shown as a line under the result. Keep it to a sentence.',
    values: true,
  },
  { value: 'BOOLEAN', label: 'Boolean', hint: 'True or false.', metadataOnly: true },
  { value: 'LIST', label: 'List', hint: 'Several short values.', values: true, metadataOnly: true },
]

export const taskSourceTexts: { value: TaskSourceText; label: string; hint: string }[] = [
  {
    value: 'ENTITY',
    label: 'The whole document',
    hint: 'Right when the model must judge an item as a whole — scoring a job posting, say.',
  },
  {
    value: 'CHUNK',
    label: 'Just the matching passage',
    hint: 'Cheaper, and right when the passage that matched is the part that matters.',
  },
]

/**
 * `slot` is presentation only: the backend renders both messages from one map, so any of these
 * resolves in either.
 */
export const taskPlaceholders: {
  name: string
  slot: 'system' | 'user'
  hint: string
  required?: boolean
}[] = [
  {
    name: 'sources',
    slot: 'user',
    required: true,
    hint: 'The results that were found, numbered and quoted. Without it the model is given nothing to work from.',
  },
  { name: 'query', slot: 'user', hint: 'The words the digest searched for.' },
  {
    name: 'today',
    slot: 'system',
    hint: 'The date the digest runs, as 2026-09-12. Without it, “this week” means nothing to the model.',
  },
  {
    name: 'fence',
    slot: 'system',
    hint: 'The marks wrapped around each result, so the prompt can say that what is inside them is data and not orders.',
  },
  {
    name: 'truncationMarker',
    slot: 'system',
    hint: 'What a result too long to fit ends with, so the model can say what it could not see instead of calling it missing.',
  },
]

export const taskPlaceholderNames = taskPlaceholders.map((p) => p.name)

const profileLabels: Record<string, string> = {
  lite: 'Fast and cheap',
  answer: 'Slower and more thorough',
  default: 'Provider default',
}

export function llmProfileLabel(profile: string): string {
  return profileLabels[profile] ?? profile
}

export const suggestedScoringFields = [
  { name: 'fit', type: 'NUMBER' as TaskFieldType, description: '0-10, where 10 means act on it today', optional: false },
  { name: 'reason', type: 'TEXT' as TaskFieldType, description: 'One sentence naming what decided the score', optional: false },
  { name: 'concern', type: 'TEXT' as TaskFieldType, description: 'The strongest reason not to, or nothing', optional: true },
]

export const TASK_DEFAULT_PROFILE = 'lite'
export const TASK_DEFAULT_CONTEXT_CHARS = 24000
export const TASK_DEFAULT_MAX_SOURCES = 10
