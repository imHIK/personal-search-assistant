import type { TaskFieldType, TaskOutput, TaskSourceText } from '@/api/types'

/**
 * Descriptors for the task editor. Like `connectors.ts`, the form renders from these — no component
 * branches on a mode or an output shape, and adding one is an entry here.
 */

export const taskOutputs: { value: TaskOutput; label: string; hint: string }[] = [
  {
    value: 'SUMMARY',
    label: 'One summary',
    hint: 'A single piece of writing about everything the digest found.',
  },
  {
    value: 'PER_ITEM',
    label: 'Notes on each result',
    hint: 'A short note on every result, shown beside it. Choose this to get scores you can scan.',
  },
]

export const taskFieldTypes: { value: TaskFieldType; label: string; hint: string }[] = [
  { value: 'NUMBER', label: 'Number', hint: 'Shown as a badge on the result. Use 0–10 for scores.' },
  { value: 'TEXT', label: 'Text', hint: 'Shown as a line under the result. Keep it to a sentence.' },
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
 * The values `AnswerPromptBuilder` substitutes into a RAW task's prompt.
 *
 * `slot` is **presentation only**. The backend renders both messages from one map, so every one of
 * these resolves in either message; the slot is just where the editor offers it, because offering
 * `{{sources}}` while someone writes the system message is noise. Typing one into the other message
 * is allowed and works — the editor does not fight it.
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

/** Every placeholder name, for telling a typo from a value that merely sits in the other message. */
export const taskPlaceholderNames = taskPlaceholders.map((p) => p.name)

/**
 * Plain names for the configured LLM profiles. Unknown profiles fall through to their own name, so a
 * deployment that adds one still gets a working picker.
 */
const profileLabels: Record<string, string> = {
  lite: 'Fast and cheap',
  answer: 'Slower and more thorough',
  default: 'Provider default',
}

export function llmProfileLabel(profile: string): string {
  return profileLabels[profile] ?? profile
}

/** Sensible starting fields for a "score each result" task — the shape `job-fit` proved out. */
export const suggestedScoringFields = [
  { name: 'fit', type: 'NUMBER' as TaskFieldType, description: '0-10, where 10 means act on it today', optional: false },
  { name: 'reason', type: 'TEXT' as TaskFieldType, description: 'One sentence naming what decided the score', optional: false },
  { name: 'concern', type: 'TEXT' as TaskFieldType, description: 'The strongest reason not to, or nothing', optional: true },
]

export const TASK_DEFAULT_PROFILE = 'lite'
export const TASK_DEFAULT_CONTEXT_CHARS = 24000
export const TASK_DEFAULT_MAX_SOURCES = 10
