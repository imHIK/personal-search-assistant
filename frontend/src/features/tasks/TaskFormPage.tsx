import { AlertTriangle, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { Task, TaskBody, TaskField, TaskFieldType, TaskOutput, TaskSourceText } from '@/api/types'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, Input, Select, Textarea } from '@/components/ui/Input'
import { PageHeader } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import {
  llmProfileLabel,
  suggestedScoringFields,
  taskFieldTypes,
  taskOutputs,
  taskSourceTexts,
  TASK_DEFAULT_CONTEXT_CHARS,
  TASK_DEFAULT_MAX_SOURCES,
  TASK_DEFAULT_PROFILE,
} from '@/config/tasks'
import { useLlmProfiles, useTask, useTaskActions } from '@/hooks/queries'

/**
 * Create or edit a task.
 *
 * The default path asks for an instruction and the shape of the reply, and the app renders both into
 * a shipped wrapper that carries the rules a user should not have to remember — that retrieved text
 * is data rather than instructions, and that the model must not draw on its own knowledge. Writing
 * the prompt outright is available under technical details, with that responsibility handed back
 * explicitly.
 */
export function TaskFormPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  // A duplicate arrives as unsaved state rather than a saved row, so abandoning it leaves nothing.
  const draft = (location.state as { draft?: Task } | null)?.draft

  const editing = Boolean(id)
  const { data: existing, isLoading, error, refetch } = useTask(id)

  if (editing && isLoading) return <SkeletonList rows={3} />
  if (editing && error) return <ErrorState error={error} onRetry={() => void refetch()} />
  if (editing && existing?.builtIn) {
    return (
      <>
        <PageHeader title={existing.name} backTo="/tasks" backLabel={labels.tasks.title} />
        <Card className="p-5 text-sm text-[var(--text-muted)]">{labels.tasks.builtInReadOnly}</Card>
      </>
    )
  }

  return (
    <Editor
      key={existing?.id ?? draft?.id ?? 'new'}
      initial={existing ?? draft}
      editingId={editing ? id : undefined}
      onDone={() => navigate('/tasks')}
    />
  )
}

function Editor({
  initial,
  editingId,
  onDone,
}: {
  initial?: Task
  editingId?: string
  onDone: () => void
}) {
  const { create, update } = useTaskActions()
  const { data: profiles } = useLlmProfiles()

  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [raw, setRaw] = useState(initial?.mode === 'RAW')
  const [instruction, setInstruction] = useState(initial?.instruction ?? '')
  const [output, setOutput] = useState<TaskOutput>(initial?.output ?? 'SUMMARY')
  const [fields, setFields] = useState<TaskField[]>(initial?.fields ?? [])
  const [system, setSystem] = useState(initial?.system ?? '')
  const [user, setUser] = useState(initial?.user ?? '')
  const [llmProfile, setLlmProfile] = useState(initial?.llmProfile ?? TASK_DEFAULT_PROFILE)
  const [sourceText, setSourceText] = useState<TaskSourceText>(initial?.sourceText ?? 'ENTITY')
  const [contextChars, setContextChars] = useState(
    initial?.contextChars || TASK_DEFAULT_CONTEXT_CHARS,
  )
  const [maxSources, setMaxSources] = useState(initial?.maxSources || TASK_DEFAULT_MAX_SOURCES)

  const pending = create.isPending || update.isPending
  const valid =
    name.trim() !== '' &&
    (raw ? system.trim() !== '' && user.includes('{{sources}}') : instruction.trim() !== '')

  const setField = (index: number, patch: Partial<TaskField>) =>
    setFields((current) => current.map((f, i) => (i === index ? { ...f, ...patch } : f)))

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!valid) return

    const body: TaskBody = {
      name: name.trim(),
      description: description.trim(),
      mode: raw ? 'RAW' : 'SIMPLE',
      instruction: raw ? null : instruction.trim(),
      output: raw ? null : output,
      fields: raw || output !== 'PER_ITEM' ? [] : fields,
      system: raw ? system : null,
      user: raw ? user : null,
      llmProfile,
      sourceText,
      contextChars,
      maxSources,
    }

    const onError = (cause: unknown) =>
      toast.error(labels.tasks.saveFailed, { description: friendlyError(cause).detail })

    if (editingId) {
      update.mutate(
        { id: editingId, body },
        { onSuccess: () => { toast.success(labels.tasks.updated); onDone() }, onError },
      )
    } else {
      create.mutate(body, {
        onSuccess: () => { toast.success(labels.tasks.created); onDone() },
        onError,
      })
    }
  }

  return (
    <>
      <PageHeader
        title={editingId ? name || labels.tasks.edit : labels.tasks.add}
        backTo="/tasks"
        backLabel={labels.tasks.title}
      />

      <Card className="p-5">
        <form onSubmit={submit} className="space-y-5">
          <Field label={labels.tasks.name} required htmlFor="task-name">
            <Input
              id="task-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={labels.tasks.namePlaceholder}
              autoFocus
            />
          </Field>

          <Field label={labels.tasks.description} htmlFor="task-description">
            <Input
              id="task-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder={labels.tasks.descriptionPlaceholder}
            />
          </Field>

          {raw ? (
            <>
              <div className="flex gap-2 rounded-lg border border-[var(--tone-wait)] bg-[var(--tone-wait-bg)] p-3">
                <AlertTriangle
                  className="mt-0.5 size-4 shrink-0 text-[var(--tone-wait)]"
                  aria-hidden
                />
                <p className="text-[11px] leading-relaxed text-[var(--text)]">
                  {labels.tasks.rawModeHint}
                </p>
              </div>

              <Field label={labels.tasks.rawSystem} required htmlFor="task-system">
                <Textarea
                  id="task-system"
                  rows={10}
                  value={system}
                  onChange={(event) => setSystem(event.target.value)}
                  className="font-mono text-[12px]"
                />
              </Field>

              <Field label={labels.tasks.rawUser} hint={labels.tasks.rawUserHint} htmlFor="task-user">
                <Textarea
                  id="task-user"
                  rows={4}
                  value={user}
                  onChange={(event) => setUser(event.target.value)}
                  className="font-mono text-[12px]"
                />
              </Field>
            </>
          ) : (
            <>
              <Field
                label={labels.tasks.instruction}
                hint={labels.tasks.instructionHint}
                required
                htmlFor="task-instruction"
              >
                <Textarea
                  id="task-instruction"
                  rows={4}
                  value={instruction}
                  onChange={(event) => setInstruction(event.target.value)}
                  placeholder={labels.tasks.instructionPlaceholder}
                />
              </Field>

              <Field label={labels.tasks.outputLabel}>
                <div className="space-y-1.5">
                  {taskOutputs.map((option) => (
                    <label key={option.value} className="flex items-start gap-2">
                      <input
                        type="radio"
                        name="task-output"
                        checked={output === option.value}
                        onChange={() => {
                          setOutput(option.value)
                          if (option.value === 'PER_ITEM' && fields.length === 0) {
                            setFields(suggestedScoringFields)
                          }
                        }}
                        className="mt-1 size-3.5 accent-[var(--accent)]"
                      />
                      <span className="text-xs">
                        <span className="font-medium">{option.label}</span>
                        <span className="block text-[11px] text-[var(--text-subtle)]">
                          {option.hint}
                        </span>
                      </span>
                    </label>
                  ))}
                </div>
              </Field>

              {output === 'PER_ITEM' && (
                <Field label={labels.tasks.fieldsLabel} hint={labels.tasks.fieldsHint}>
                  <div className="space-y-2">
                    {fields.map((field, index) => (
                      <div
                        key={index}
                        className="flex flex-wrap items-center gap-2 rounded-lg border border-[var(--border)] p-2"
                      >
                        <Input
                          value={field.name}
                          onChange={(event) => setField(index, { name: event.target.value })}
                          placeholder={labels.tasks.fieldName}
                          className="h-8 w-28 text-[12px]"
                        />
                        <Select
                          value={field.type}
                          onChange={(event) =>
                            setField(index, { type: event.target.value as TaskFieldType })
                          }
                          className="h-8 w-28 text-[12px]"
                        >
                          {taskFieldTypes.map((option) => (
                            <option key={option.value} value={option.value}>
                              {option.label}
                            </option>
                          ))}
                        </Select>
                        <Input
                          value={field.description}
                          onChange={(event) => setField(index, { description: event.target.value })}
                          placeholder={labels.tasks.fieldDescription}
                          className="h-8 min-w-40 flex-1 text-[12px]"
                        />
                        <label className="flex items-center gap-1.5 text-[11px] text-[var(--text-muted)]">
                          <input
                            type="checkbox"
                            checked={field.optional}
                            onChange={(event) =>
                              setField(index, { optional: event.target.checked })
                            }
                            className="size-3.5 accent-[var(--accent)]"
                          />
                          {labels.tasks.fieldOptional}
                        </label>
                        <Button
                          type="button"
                          variant="ghost"
                          size="iconSm"
                          aria-label={labels.tasks.removeField}
                          onClick={() => setFields(fields.filter((_, i) => i !== index))}
                        >
                          <Trash2 />
                        </Button>
                      </div>
                    ))}
                    <div className="flex gap-2">
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={() =>
                          setFields([
                            ...fields,
                            { name: '', type: 'TEXT', description: '', optional: false },
                          ])
                        }
                      >
                        <Plus />
                        {labels.tasks.addField}
                      </Button>
                      {fields.length === 0 && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          onClick={() => setFields(suggestedScoringFields)}
                        >
                          {labels.tasks.useSuggested}
                        </Button>
                      )}
                    </div>
                  </div>
                </Field>
              )}
            </>
          )}

          <Field label={labels.tasks.judgeBy}>
            <Select
              value={sourceText}
              onChange={(event) => setSourceText(event.target.value as TaskSourceText)}
              className="h-9 w-full max-w-sm text-[13px]"
            >
              {taskSourceTexts.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
            <span className="mt-1 block text-[11px] text-[var(--text-subtle)]">
              {taskSourceTexts.find((option) => option.value === sourceText)?.hint}
            </span>
          </Field>

          <Field label={labels.tasks.quality} hint={labels.tasks.qualityHint}>
            <Select
              value={llmProfile}
              onChange={(event) => setLlmProfile(event.target.value)}
              className="h-9 w-full max-w-sm text-[13px]"
            >
              {(profiles ?? [llmProfile]).map((profile) => (
                <option key={profile} value={profile}>
                  {llmProfileLabel(profile)}
                </option>
              ))}
            </Select>
            <Technical>
              <span className="mt-1 block text-[11px] text-[var(--text-subtle)]">
                app.llm.profile.{llmProfile}
              </span>
            </Technical>
          </Field>

          <p className="rounded-lg bg-[var(--surface-sunken)] p-3 text-[11px] leading-relaxed text-[var(--text-muted)]">
            {labels.tasks.costNote}
          </p>

          <Technical>
            <div className="space-y-4 rounded-lg border border-dashed border-[var(--border)] p-3">
              <Toggle checked={raw} onCheckedChange={setRaw} label={labels.tasks.rawMode} />
              <div className="flex flex-wrap gap-4">
                <label className="space-y-1">
                  <span className="block text-xs font-medium text-[var(--text-muted)]">
                    {labels.tasks.contextChars}
                  </span>
                  <Input
                    type="number"
                    value={contextChars}
                    onChange={(event) => setContextChars(Number(event.target.value))}
                    className="h-8 w-32 text-[12px]"
                  />
                </label>
                <label className="space-y-1">
                  <span className="block text-xs font-medium text-[var(--text-muted)]">
                    {labels.tasks.maxSources}
                  </span>
                  <Input
                    type="number"
                    value={maxSources}
                    onChange={(event) => setMaxSources(Number(event.target.value))}
                    className="h-8 w-24 text-[12px]"
                  />
                </label>
              </div>
            </div>
          </Technical>

          <div className="flex gap-2 pt-1">
            <Button type="submit" variant="primary" loading={pending} disabled={!valid}>
              {editingId ? labels.tasks.save : labels.tasks.create}
            </Button>
            <Button type="button" variant="ghost" onClick={onDone}>
              {labels.common.cancel}
            </Button>
          </div>
        </form>
      </Card>
    </>
  )
}
