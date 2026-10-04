import { z } from 'zod'

export type FieldKind =
  | 'text'
  | 'textarea'
  | 'secret'
  | 'number'
  | 'boolean'
  | 'list'
  | 'picklist'
  | 'select'
  | 'json'

export interface FieldSpec {
  name: string
  kind: FieldKind
  label: string
  hint?: string
  placeholder?: string
  required?: boolean
  technical?: boolean
  /**
   * `select` and `picklist`. `values`: a row that stands for several stored entries, such as every
   * spelling of a city.
   */
  options?: { value: string; label: string; note?: string; values?: string[] }[]
  /** `picklist` only: label for the free-text row that takes values outside `options`. */
  addOwnLabel?: string
  /** `picklist` only: plural noun for the catalog toggle ("Choose from 13 <noun>"). */
  browseNoun?: string
  /** `number` only. */
  min?: number
  max?: number
  /** `number` only. Absent is the browser's 1, which rejects decimals; `any` accepts them. */
  step?: number | 'any'
  validate?: (value: unknown) => string | undefined
}

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
      case 'picklist':
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

export function emptyValue(field: FieldSpec): unknown {
  switch (field.kind) {
    case 'boolean':
      return false
    case 'list':
    case 'picklist':
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
