import { ChevronDown, ChevronUp, Eye, EyeOff, Plus, X } from 'lucide-react'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Field, Input, Select, Textarea } from '@/components/ui/Input'
import { useTechnicalDetails } from '@/components/TechnicalDetails'
import type { FieldSpec } from '@/config/fields'
import { labels } from '@/config/labels'
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

    case 'picklist':
      return <PicklistInput id={id} field={field} value={value} disabled={disabled} onChange={onChange} />

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
 * A checkbox list of known values plus a free-text row for anything else.
 *
 * It replaces a bare textarea for values the user cannot be expected to invent — a job board handle
 * is "the name as it appears in their careers URL", which is unknowable without going and looking,
 * and a typo is indistinguishable from a company that genuinely has no board. The catalog covers the
 * common case; the add-your-own row keeps every value the textarea accepted, so nothing is lost and
 * the lookup checker above still feeds the same list.
 *
 * The catalog panel expands inline rather than floating: it lives inside a card that scrolls, and an
 * absolutely-positioned list of thirty rows would be clipped or would cover the fields under it.
 */
function PicklistInput({
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
  const selected = Array.isArray(value) ? (value as string[]) : []
  const options = field.options ?? []
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const [draft, setDraft] = useState('')

  const has = (candidate: string) =>
    selected.some((entry) => entry.toLowerCase() === candidate.toLowerCase())

  const add = (...candidates: string[]) => {
    const additions = candidates.map((c) => c.trim()).filter((c) => c && !has(c))
    if (additions.length === 0) return
    onChange([...selected, ...additions])
  }

  const remove = (...candidates: string[]) => {
    const dropped = new Set(candidates.map((c) => c.toLowerCase()))
    onChange(selected.filter((entry) => !dropped.has(entry.toLowerCase())))
  }

  /** What one catalog row stands for — usually itself, sometimes a group of accepted spellings. */
  const entriesOf = (option: { value: string; values?: string[] }) => option.values ?? [option.value]

  const needle = filter.trim().toLowerCase()
  const matching = needle
    ? options.filter(
        (option) =>
          option.label.toLowerCase().includes(needle) || option.value.toLowerCase().includes(needle),
      )
    : options

  return (
    <div className="space-y-2.5">
      {selected.length > 0 && (
        <ul className="flex flex-wrap gap-1.5">
          {selected.map((entry) => {
            const known = options.find((option) => entriesOf(option).includes(entry))
            return (
              <li key={entry}>
                <span className="inline-flex items-center gap-1 rounded-full bg-[var(--bg-subtle)] py-1 pl-2.5 pr-1 text-xs">
                  {known && entriesOf(known).length === 1 ? known.label : entry}
                  {known?.note && (
                    <span className="text-[var(--text-subtle)]">{known.note}</span>
                  )}
                  <button
                    type="button"
                    disabled={disabled}
                    aria-label={`${labels.common.remove} ${entry}`}
                    className="rounded-full p-0.5 text-[var(--text-subtle)] transition-colors hover:bg-[var(--surface-hover)] hover:text-[var(--text)]"
                    onClick={() => remove(entry)}
                  >
                    <X className="size-3" aria-hidden />
                  </button>
                </span>
              </li>
            )
          })}
        </ul>
      )}

      {options.length > 0 && (
        <div className="rounded-lg border border-[var(--border)]">
          <button
            type="button"
            id={id}
            aria-expanded={open}
            disabled={disabled}
            className="flex w-full items-center justify-between px-3 py-2 text-left text-sm text-[var(--text-muted)] transition-colors hover:text-[var(--text)]"
            onClick={() => setOpen((current) => !current)}
          >
            {labels.picklist.browse(options.length, field.browseNoun ?? labels.picklist.defaultNoun)}
            {open ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </button>

          {open && (
            <div className="border-t border-[var(--border)] p-2">
              <Input
                value={filter}
                placeholder={labels.picklist.filter}
                disabled={disabled}
                autoComplete="off"
                onChange={(event) => setFilter(event.target.value)}
              />
              <ul className="mt-2 max-h-56 space-y-0.5 overflow-y-auto">
                {matching.map((option) => (
                  <li key={option.value}>
                    <label className="flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 text-sm transition-colors hover:bg-[var(--surface-hover)]">
                      <input
                        type="checkbox"
                        className="size-4 rounded border-[var(--border-strong)] accent-[var(--accent)]"
                        checked={entriesOf(option).every(has)}
                        disabled={disabled}
                        onChange={(event) =>
                          event.target.checked
                            ? add(...entriesOf(option))
                            : remove(...entriesOf(option))
                        }
                      />
                      <span className="flex-1">{option.label}</span>
                      {option.note && (
                        <span className="text-xs text-[var(--text-subtle)]">{option.note}</span>
                      )}
                    </label>
                  </li>
                ))}
                {matching.length === 0 && (
                  <li className="px-2 py-3 text-xs text-[var(--text-muted)]">
                    {labels.picklist.noMatch}
                  </li>
                )}
              </ul>
            </div>
          )}
        </div>
      )}

      <div className="flex gap-2">
        <Input
          value={draft}
          placeholder={field.placeholder}
          disabled={disabled}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            // Enter adds the entry instead of submitting the surrounding form — losing a
            // half-typed list to an accidental submit is the worst outcome here.
            if (event.key !== 'Enter') return
            event.preventDefault()
            add(draft)
            setDraft('')
          }}
        />
        <Button
          type="button"
          variant="secondary"
          disabled={disabled || !draft.trim()}
          onClick={() => {
            add(draft)
            setDraft('')
          }}
        >
          <Plus />
          {field.addOwnLabel ?? labels.picklist.add}
        </Button>
      </div>
    </div>
  )
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
