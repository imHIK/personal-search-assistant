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
