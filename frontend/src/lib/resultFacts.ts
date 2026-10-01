import type { Blob } from '@/api/types'
import type { ResultFieldSpec } from '@/config/connectors'
import { relativeTime } from '@/lib/utils'

/** Turns a connector's declared `resultFields` into display strings; unknown or empty values drop. */
export function resultFacts(fields: ResultFieldSpec[] | undefined, metadata: Blob | null | undefined): string[] {
  const facts: string[] = []
  for (const field of fields ?? []) {
    const value = metadata?.[field.key]
    if (value === null || value === undefined || value === '') continue
    switch (field.kind) {
      case 'text':
        if (typeof value === 'string' || typeof value === 'number') facts.push(String(value))
        break
      case 'flag':
        if (value === true && field.label) facts.push(field.label)
        break
      case 'option': {
        const option = field.options?.find((candidate) => candidate.value === value)
        if (option) facts.push(option.label)
        break
      }
      case 'date': {
        const iso = typeof value === 'number' ? new Date(value).toISOString() : String(value)
        const when = relativeTime(iso)
        if (when) facts.push(field.label ? `${field.label} ${when}` : when)
        break
      }
    }
  }
  return facts
}
