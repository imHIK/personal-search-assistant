import { Eye, EyeOff } from 'lucide-react'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Field, Input, Select, Textarea } from '@/components/ui/Input'
import { useTechnicalDetails } from '@/components/TechnicalDetails'
import type { FieldSpec } from '@/config/fields'
import { cn } from '@/lib/utils'

/**
 * Renders a list of {@link FieldSpec} into controls, and reads/writes them as a flat blob.
 *
 * This is what keeps connector knowledge out of components: the wizard, the account form and the
 * settings form all render `<SchemaForm fields={descriptor.inputFields} …/>`, so adding Slack —
 * or adding a field to Gmail — never touches a component. Fields marked `technical` are hidden
 * unless the Technical details toggle is on.
 */

export type FormValues = Record<string, unknown>

interface SchemaFormProps {
  fields: FieldSpec[]
  values: FormValues
  onChange: (values: FormValues) => void
  errors?: Record<string, string>
  disabled?: boolean
  className?: string
}

export function SchemaForm({
  fields,
  values,
  onChange,
  errors = {},
  disabled,
  className,
}: SchemaFormProps) {
  const technical = useTechnicalDetails()
  const visible = fields.filter((field) => technical || !field.technical)

  if (visible.length === 0) return null

  const set = (name: string, value: unknown) => onChange({ ...values, [name]: value })

  return (
    <div className={cn('space-y-4', className)}>
      {visible.map((field) => (
        <SchemaField
          key={field.name}
          field={field}
          value={values[field.name]}
          error={errors[field.name]}
          disabled={disabled}
          onChange={(value) => set(field.name, value)}
        />
      ))}
    </div>
  )
}

function SchemaField({
  field,
  value,
  error,
  disabled,
  onChange,
}: {
  field: FieldSpec
  value: unknown
  error?: string
  disabled?: boolean
  onChange: (value: unknown) => void
}) {
  const id = useId()

  return (
    <Field
      label={field.label}
      hint={field.hint}
      error={error}
      required={field.required}
      htmlFor={id}
    >
      <Control id={id} field={field} value={value} disabled={disabled} onChange={onChange} />
    </Field>
  )
}

function Control({
  id,
  field,
  value,
  disabled,
  onChange,
}: {
  id: string
  field: FieldSpec
  value: unknown
  disabled?: boolean
  onChange: (value: unknown) => void
}) {
  switch (field.kind) {
    case 'secret':
      return <SecretInput id={id} field={field} value={value} disabled={disabled} onChange={onChange} />

    case 'textarea':
      return (
        <Textarea
          id={id}
          value={String(value ?? '')}
          placeholder={field.placeholder}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
        />
      )

    case 'number':
      return (
        <Input
          id={id}
          type="number"
          inputMode="numeric"
          min={field.min}
          max={field.max}
          value={value === undefined || value === null ? '' : String(value)}
          placeholder={field.placeholder}
          disabled={disabled}
          onChange={(event) =>
            onChange(event.target.value === '' ? undefined : Number(event.target.value))
          }
        />
      )

    case 'boolean':
      return (
        <label className="flex cursor-pointer items-center gap-2.5 text-sm text-[var(--text-muted)]">
          <input
            id={id}
            type="checkbox"
            className="size-4 rounded border-[var(--border-strong)] accent-[var(--accent)]"
            checked={Boolean(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.checked)}
          />
          {field.placeholder ?? 'Enabled'}
        </label>
      )

    case 'list':
      // One entry per line reads far better than comma-separated for paths and label ids, both of
      // which routinely contain commas and spaces.
      return (
        <Textarea
          id={id}
          value={Array.isArray(value) ? (value as string[]).join('\n') : ''}
          placeholder={field.placeholder}
          disabled={disabled}
          onChange={(event) =>
            onChange(
              event.target.value
                .split('\n')
                .map((line) => line.trim())
                .filter(Boolean),
            )
          }
        />
      )

    case 'select':
      return (
        <Select
          id={id}
          value={String(value ?? '')}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
        >
          <option value="">Default</option>
          {field.options?.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
      )

    case 'json':
      return <JsonInput id={id} value={value} disabled={disabled} onChange={onChange} />

    default:
      return (
        <Input
          id={id}
          value={String(value ?? '')}
          placeholder={field.placeholder}
          disabled={disabled}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => onChange(event.target.value)}
        />
      )
  }
}

/**
 * Masked by default with a reveal toggle. The backend returns `auth`/`config` unredacted on every
 * read, so anything credential-shaped must never be rendered in the clear by accident.
 */
function SecretInput({
  id,
  field,
  value,
  disabled,
  onChange,
}: {
  id: string
  field: FieldSpec
  value: unknown
  disabled?: boolean
  onChange: (value: unknown) => void
}) {
  const [revealed, setRevealed] = useState(false)

  return (
    <div className="relative">
      <Input
        id={id}
        type={revealed ? 'text' : 'password'}
        value={String(value ?? '')}
        placeholder={field.placeholder}
        disabled={disabled}
        autoComplete="off"
        spellCheck={false}
        className="pr-10 font-mono text-[13px]"
        onChange={(event) => onChange(event.target.value)}
      />
      <Button
        type="button"
        variant="ghost"
        size="iconSm"
        className="absolute right-1 top-1"
        aria-label={revealed ? 'Hide' : 'Show'}
        onClick={() => setRevealed((current) => !current)}
      >
        {revealed ? <EyeOff /> : <Eye />}
      </Button>
    </div>
  )
}

/** Free-form JSON escape hatch, for connector keys the descriptor doesn't name yet. */
function JsonInput({
  id,
  value,
  disabled,
  onChange,
}: {
  id: string
  value: unknown
  disabled?: boolean
  onChange: (value: unknown) => void
}) {
  const [text, setText] = useState(() => {
    const object = (value ?? {}) as Record<string, unknown>
    return Object.keys(object).length === 0 ? '' : JSON.stringify(object, null, 2)
  })
  const [invalid, setInvalid] = useState(false)

  return (
    <div className="space-y-1.5">
      <Textarea
        id={id}
        value={text}
        placeholder={'{\n  "key": "value"\n}'}
        disabled={disabled}
        spellCheck={false}
        className="font-mono text-[12px]"
        onChange={(event) => {
          const next = event.target.value
          setText(next)
          if (!next.trim()) {
            setInvalid(false)
            onChange({})
            return
          }
          try {
            onChange(JSON.parse(next) as Record<string, unknown>)
            setInvalid(false)
          } catch {
            setInvalid(true)
          }
        }}
      />
      {invalid && <p className="text-xs text-[var(--tone-alert)]">Not valid JSON yet</p>}
    </div>
  )
}
