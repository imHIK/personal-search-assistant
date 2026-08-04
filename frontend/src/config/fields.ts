import { z } from 'zod'

/**
 * A declarative field description. `SchemaForm` renders one of these into the right control and
 * derives its zod validator from the same object, so a form is data rather than JSX. Adding a
 * field to a connector means appending a FieldSpec — no component changes anywhere.
 */
export type FieldKind =
  | 'text'
  | 'textarea'
  | 'secret'
  | 'number'
  | 'boolean'
  | 'list'
  | 'select'
  | 'json'

export interface FieldSpec {
  /** Key written into the target blob (`inputs`, `auth` or `config`). */
  name: string
  kind: FieldKind
  label: string
  /** One line under the control explaining what it is, in user terms. */
  hint?: string
  placeholder?: string
  required?: boolean
  /** Hide unless the Technical details toggle is on — for internals a user shouldn't need. */
  technical?: boolean
  /** `select` only. */
  options?: { value: string; label: string }[]
  /** `number` only. */
  min?: number
  max?: number
  /** Extra validation beyond the kind's default, e.g. an absolute-path check. */
  validate?: (value: unknown) => string | undefined
}

/** Build a zod object schema from a field list. Kept next to FieldSpec so the two can't drift. */
export function schemaFor(fields: FieldSpec[]): z.ZodType<Record<string, unknown>> {
  const shape: Record<string, z.ZodTypeAny> = {}

  for (const field of fields) {
    let schema: z.ZodTypeAny

    switch (field.kind) {
      case 'number':
        schema = z.coerce.number({ invalid_type_error: 'Enter a number' })
        if (field.min !== undefined) schema = (schema as z.ZodNumber).min(field.min)
        if (field.max !== undefined) schema = (schema as z.ZodNumber).max(field.max)
        break
      case 'boolean':
        schema = z.boolean()
        break
      case 'list':
        schema = z.array(z.string())
        break
      case 'json':
        schema = z.record(z.unknown())
        break
      default:
        schema = z.string()
        if (field.required) {
          schema = (schema as z.ZodString).min(1, `${field.label} is required`)
        }
    }

    if (field.validate) {
      schema = schema.superRefine((value, ctx) => {
        const message = field.validate?.(value)
        if (message) ctx.addIssue({ code: z.ZodIssueCode.custom, message })
      })
    }

    shape[field.name] = field.required ? schema : schema.optional()
  }

  return z.object(shape)
}

/** Sensible empty value per kind, used to seed a fresh form. */
export function emptyValue(field: FieldSpec): unknown {
  switch (field.kind) {
    case 'boolean':
      return false
    case 'list':
      return []
    case 'json':
      return {}
    case 'number':
      return undefined
    default:
      return ''
  }
}

export function initialValues(fields: FieldSpec[], existing?: Record<string, unknown>) {
  const values: Record<string, unknown> = {}
  for (const field of fields) {
    const current = existing?.[field.name]
    values[field.name] = current === undefined || current === null ? emptyValue(field) : current
  }
  return values
}

/**
 * Strip empty values so a patch body doesn't send `""` for fields the user left alone. The
 * backend treats absent and null identically ("unchanged"), so omitting is the correct encoding.
 */
export function pruneEmpty(values: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value) && value.length === 0) continue
    if (typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0) continue
    out[key] = value
  }
  return out
}
