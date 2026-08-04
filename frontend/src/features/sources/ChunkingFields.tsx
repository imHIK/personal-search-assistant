import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { chunkingStrategies } from '@/config/constants'
import type { FieldSpec } from '@/config/fields'

/**
 * Chunking controls. Deliberately kept behind an "Advanced" disclosure everywhere it appears:
 * chunk size and overlap are indexing-quality tuning knobs, not something a user has an opinion
 * about, and the server-side defaults are already the right answer for almost everything.
 *
 * Changing these does NOT re-chunk what is already indexed — the backend treats a chunking edit
 * as a direct update, and existing items keep their old chunks until individually reprocessed.
 */
export const chunkingFields: FieldSpec[] = [
  {
    name: 'chunkingStrategy',
    kind: 'select',
    label: 'How text is split',
    hint: 'Leave on default unless you have a reason. Recursive suits nearly all documents.',
    options: chunkingStrategies.map((strategy) => ({
      value: strategy.value,
      label: `${strategy.label} — ${strategy.hint}`,
    })),
  },
  {
    name: 'chunkingMaxSize',
    kind: 'number',
    label: 'Chunk size',
    hint: 'Characters per chunk (tokens, if the token strategy is selected). Blank uses the server default.',
    min: 100,
    max: 10000,
  },
  {
    name: 'chunkingOverlap',
    kind: 'number',
    label: 'Overlap',
    hint: 'How much each chunk repeats from the previous one, so a sentence split across a boundary is still findable.',
    min: 0,
    max: 2000,
  },
  {
    name: 'chunkingSeparators',
    kind: 'list',
    label: 'Separators',
    hint: 'Only used by the character strategy. One per line.',
    technical: true,
  },
]

export function ChunkingFields({
  values,
  onChange,
  disabled,
}: {
  values: FormValues
  onChange: (values: FormValues) => void
  disabled?: boolean
}) {
  return (
    <SchemaForm fields={chunkingFields} values={values} onChange={onChange} disabled={disabled} />
  )
}
