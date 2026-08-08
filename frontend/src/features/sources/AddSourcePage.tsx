import { Check, ChevronRight, Plus } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import type { CreateKnowledgeBody, SourceType } from '@/api/types'
import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { Field, Input, Select } from '@/components/ui/Input'
import { PageHeader } from '@/components/ui/PageHeader'
import { ErrorState } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { connectors, connectorFor } from '@/config/connectors'
import { initialValues, pruneEmpty, schemaFor } from '@/config/fields'
import { labels } from '@/config/labels'
import { useConnections, useCreateKnowledge } from '@/hooks/queries'
import { cn } from '@/lib/utils'
import { ChunkingFields } from './ChunkingFields'
import { ScheduleField, scheduleToBody, type ScheduleValue } from './ScheduleField'

/**
 * Add a source. Rendered entirely from the connector descriptors — there is no branch on
 * SourceType anywhere in this file, so a new connector appears here as soon as its descriptor
 * has `implemented: true`.
 */
export function AddSourcePage() {
  const navigate = useNavigate()
  const create = useCreateKnowledge()

  const [type, setType] = useState<SourceType | null>(null)
  const [name, setName] = useState('')
  const [connectionId, setConnectionId] = useState<string>('')
  const [inputs, setInputs] = useState<FormValues>({})
  const [inputErrors, setInputErrors] = useState<Record<string, string>>({})
  const [schedule, setSchedule] = useState<ScheduleValue>({ preset: '1h', cron: '' })
  const [backfill, setBackfill] = useState(true)
  const [chunking, setChunking] = useState<FormValues>({})
  const [showAdvanced, setShowAdvanced] = useState(false)
  /** Set when the server answers 200 but parks the source in ERROR. */
  const [activationError, setActivationError] = useState<string | null>(null)

  const descriptor = type ? connectorFor(type) : null
  const { data: connections } = useConnections(type ?? undefined)

  const eligible = useMemo(
    () => (connections ?? []).filter((connection) => connection.type === type),
    [connections, type],
  )

  // Preselect the default account so the common case is zero clicks.
  useEffect(() => {
    if (!descriptor?.requiresConnection) return
    if (connectionId) return
    const preferred = eligible.find(isDefaultConnection) ?? eligible[0]
    if (preferred) setConnectionId(preferred.id)
  }, [descriptor, eligible, connectionId])

  useEffect(() => {
    if (!descriptor) return
    setInputs(initialValues(descriptor.inputFields))
    setInputErrors({})
  }, [descriptor])

  if (!type || !descriptor) {
    return (
      <div className="mx-auto max-w-3xl">
        <PageHeader
          title={labels.wizard.title}
          subtitle={labels.wizard.stepType}
          backTo="/knowledge"
          backLabel={labels.sources.title}
        />
        <div className="grid gap-3 sm:grid-cols-2">
          {connectors.map((candidate) => {
            const Icon = candidate.icon
            return (
              <button
                key={candidate.id}
                type="button"
                disabled={!candidate.implemented}
                onClick={() => {
                  setType(candidate.id)
                  setName(candidate.label)
                }}
                className={cn(
                  'flex items-start gap-3.5 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-4 text-left transition-colors',
                  candidate.implemented
                    ? 'hover:border-[var(--accent)] hover:bg-[var(--surface-hover)]'
                    : 'cursor-not-allowed opacity-55',
                )}
              >
                <div className="rounded-lg bg-[var(--bg-subtle)] p-2">
                  <Icon className="size-4 text-[var(--text-muted)]" aria-hidden />
                </div>
                <div className="min-w-0 flex-1 space-y-1">
                  <p className="flex items-center gap-2 text-sm font-medium text-[var(--text)]">
                    {candidate.label}
                    {!candidate.implemented && (
                      <span className="rounded-full bg-[var(--tone-neutral-bg)] px-2 py-0.5 text-[10px] font-medium text-[var(--tone-neutral)]">
                        {labels.wizard.comingSoon}
                      </span>
                    )}
                  </p>
                  <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                    {candidate.description}
                  </p>
                </div>
                {candidate.implemented && (
                  <ChevronRight className="mt-0.5 size-4 shrink-0 text-[var(--text-subtle)]" aria-hidden />
                )}
              </button>
            )
          })}
        </div>
      </div>
    )
  }

  const needsAccount = descriptor.requiresConnection && eligible.length === 0

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setActivationError(null)

    const parsed = schemaFor(descriptor.inputFields).safeParse(inputs)
    if (!parsed.success) {
      const errors: Record<string, string> = {}
      for (const issue of parsed.error.issues) {
        const key = issue.path[0]
        if (typeof key === 'string') errors[key] = issue.message
      }
      setInputErrors(errors)
      return
    }
    setInputErrors({})

    const body: CreateKnowledgeBody = {
      name: name.trim() || descriptor.label,
      type: descriptor.id,
      connectionId: descriptor.requiresConnection ? connectionId || null : null,
      inputs: pruneEmpty(inputs),
      backfillEnabled: backfill,
      ...scheduleToBody(schedule),
      chunkingStrategy: (chunking.chunkingStrategy as string) || null,
      chunkingMaxSize: (chunking.chunkingMaxSize as number | undefined) ?? null,
      chunkingOverlap: (chunking.chunkingOverlap as number | undefined) ?? null,
      chunkingSeparators: (chunking.chunkingSeparators as string[] | undefined) ?? null,
    }

    create.mutate(body, {
      onSuccess: (knowledge) => {
        // The API answers 200 even when verify/discover failed — the failure shows up as
        // status ERROR with a lastError. Treating 200 as success here would silently create a
        // dead source, so stay on the form and surface the reason.
        if (knowledge.status === 'ERROR') {
          setActivationError(knowledge.lastError ?? 'The source could not be reached.')
          return
        }
        toast.success(`Connected "${knowledge.name}"`)
        navigate(`/knowledge/${knowledge.id}`)
      },
    })
  }

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader
        title={labels.wizard.title}
        subtitle={descriptor.description}
        backTo="/knowledge"
        backLabel={labels.sources.title}
        actions={
          <Button variant="ghost" size="sm" onClick={() => setType(null)}>
            Change type
          </Button>
        }
      />

      <form onSubmit={submit} className="space-y-5">
        <Card>
          <CardHeader>
            <CardTitle>{labels.settings.basics}</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <Field label="Name" hint="How this appears in your list." required htmlFor="source-name">
              <Input
                id="source-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder={descriptor.label}
                autoFocus
              />
            </Field>

            {descriptor.requiresConnection && (
              <Field
                label={labels.wizard.stepAccount}
                hint={needsAccount ? undefined : 'Reused across every source of this type.'}
                required
                htmlFor="source-connection"
              >
                {needsAccount ? (
                  <div className="rounded-lg border border-dashed border-[var(--border)] px-4 py-4 text-center">
                    <p className="text-sm text-[var(--text-muted)]">
                      No {descriptor.label} account connected yet.
                    </p>
                    <Button asChild variant="secondary" size="sm" className="mt-3">
                      <Link to="/connections/new">
                        <Plus />
                        {labels.accounts.add}
                      </Link>
                    </Button>
                  </div>
                ) : (
                  <Select
                    id="source-connection"
                    value={connectionId}
                    onChange={(event) => setConnectionId(event.target.value)}
                  >
                    {eligible.map((connection) => (
                      <option key={connection.id} value={connection.id}>
                        {connection.name}
                        {isDefaultConnection(connection) ? ' (default)' : ''}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
          </CardBody>
        </Card>

        {descriptor.inputFields.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle>{labels.wizard.stepInputs}</CardTitle>
            </CardHeader>
            <CardBody>
              <SchemaForm
                fields={descriptor.inputFields}
                values={inputs}
                onChange={setInputs}
                errors={inputErrors}
              />
            </CardBody>
          </Card>
        )}

        <Card>
          <CardHeader>
            <CardTitle>{labels.wizard.stepSchedule}</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <ScheduleField value={schedule} onChange={setSchedule} />
            <Toggle
              checked={backfill}
              onCheckedChange={setBackfill}
              label={labels.wizard.backfill}
              hint={labels.wizard.backfillHint}
            />
          </CardBody>
        </Card>

        <Card>
          <CardHeader className="flex items-center justify-between gap-3">
            <CardTitle>{labels.wizard.advanced}</CardTitle>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setShowAdvanced((value) => !value)}
              aria-expanded={showAdvanced}
            >
              {showAdvanced ? labels.common.showLess : labels.common.showMore}
            </Button>
          </CardHeader>
          {showAdvanced && (
            <CardBody className="space-y-4">
              <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                {labels.wizard.advancedHint}
              </p>
              <ChunkingFields values={chunking} onChange={setChunking} />
            </CardBody>
          )}
        </Card>

        {activationError && (
          <ErrorState error={new Error(activationError)} compact />
        )}
        {create.error && <ErrorState error={create.error} compact />}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => navigate('/knowledge')}>
            {labels.common.cancel}
          </Button>
          <Button
            variant="primary"
            type="submit"
            loading={create.isPending}
            disabled={needsAccount}
          >
            <Check />
            {create.isPending ? labels.wizard.creating : labels.wizard.create}
          </Button>
        </div>
      </form>
    </div>
  )
}
