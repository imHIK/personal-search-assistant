import { AlertTriangle } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { Knowledge, PatchKnowledgeBody } from '@/api/types'
import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { Field, Input } from '@/components/ui/Input'
import { ErrorState } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { connectorFor } from '@/config/connectors'
import { schedulePresetFor } from '@/config/constants'
import { features } from '@/config/features'
import { initialValues } from '@/config/fields'
import { labels } from '@/config/labels'
import { usePatchKnowledge } from '@/hooks/queries'
import { ChunkingFields } from './ChunkingFields'
import { ScheduleField, scheduleToBody, type ScheduleValue } from './ScheduleField'

/**
 * Grouped by consequence, not by field.
 *
 * The backend routes an edit down one of two very different paths: config-class changes (name,
 * schedule, chunking) are a single in-place write, while provisioning-class changes (inputs, auth,
 * backfill off→on) pause the source, re-verify the account, re-discover and reconcile. Those are
 * separated here with an explicit warning, because the second kind can take minutes and briefly
 * takes the source offline.
 */
export function SettingsTab({ knowledge }: { knowledge: Knowledge }) {
  const descriptor = connectorFor(knowledge.connectorDetails.type)
  const patch = usePatchKnowledge(knowledge.id)
  const readOnly = knowledge.status === 'DELETED'

  const [name, setName] = useState(knowledge.name)
  const [schedule, setSchedule] = useState<ScheduleValue>(() => ({
    preset: schedulePresetFor(
      knowledge.config.scheduleSettings.interval,
      knowledge.config.scheduleSettings.cron,
      knowledge.config.scheduleSettings.enabled,
    ),
    cron: knowledge.config.scheduleSettings.cron ?? '',
  }))
  const [chunking, setChunking] = useState<FormValues>(() => ({
    chunkingStrategy: knowledge.config.chunking.strategy ?? '',
    chunkingMaxSize: knowledge.config.chunking.maxSize ?? undefined,
    chunkingOverlap: knowledge.config.chunking.overlap ?? undefined,
    chunkingSeparators: knowledge.config.chunking.separators ?? [],
  }))
  const [inputs, setInputs] = useState<FormValues>(() =>
    initialValues(descriptor.inputFields, knowledge.inputs as Record<string, unknown>),
  )
  const [backfill, setBackfill] = useState(knowledge.config.backfill.enabled)

  const save = (body: PatchKnowledgeBody, message: string) =>
    patch.mutate(body, {
      onSuccess: (updated) => {
        // A provisioning edit re-verifies, and a failure there lands the source in ERROR with a
        // 200 — same trap as create. Report it as a failure, because it is one.
        if (updated.status === 'ERROR') {
          toast.error("Saved, but the source couldn't be reached", {
            description: updated.lastError ?? undefined,
          })
          return
        }
        toast.success(message)
      },
      onError: () => {
        /* rendered inline by ErrorState below */
      },
    })

  if (readOnly) {
    return (
      <Card>
        <CardBody>
          <p className="text-sm text-[var(--text-muted)]">{labels.settings.readOnly}</p>
        </CardBody>
      </Card>
    )
  }

  return (
    <div className="max-w-2xl space-y-5">
      <Card>
        <CardHeader>
          <CardTitle>{labels.settings.basics}</CardTitle>
        </CardHeader>
        <CardBody className="space-y-4">
          <Field label={labels.settings.name} required htmlFor="source-name">
            <Input
              id="source-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>
          <Field
            label={labels.settings.connector}
            hint={labels.settings.connectorFixed}
            required
            htmlFor="source-connector"
          >
            <p id="source-connector" className="flex h-9 items-center text-sm text-[var(--text-muted)]">
              {descriptor.label}
            </p>
          </Field>
          <div className="flex justify-end">
            <Button
              variant="primary"
              size="sm"
              loading={patch.isPending}
              disabled={name.trim() === knowledge.name}
              onClick={() => save({ name: name.trim() }, labels.settings.saved)}
            >
              {labels.settings.save}
            </Button>
          </div>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{labels.settings.schedule}</CardTitle>
        </CardHeader>
        <CardBody className="space-y-4">
          <ScheduleField value={schedule} onChange={setSchedule} />
          <div className="flex justify-end">
            <Button
              variant="primary"
              size="sm"
              loading={patch.isPending}
              onClick={() => save(scheduleToBody(schedule), labels.settings.saved)}
            >
              {labels.settings.save}
            </Button>
          </div>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{labels.settings.chunking}</CardTitle>
        </CardHeader>
        <CardBody className="space-y-4">
          <p className="text-xs leading-relaxed text-[var(--text-muted)]">
            {labels.wizard.advancedHint} Changing these affects newly processed items only —
            existing ones keep their current form until you reprocess them.
          </p>
          <ChunkingFields values={chunking} onChange={setChunking} />
          <div className="flex justify-end">
            <Button
              variant="primary"
              size="sm"
              loading={patch.isPending}
              onClick={() =>
                save(
                  {
                    chunkingStrategy: (chunking.chunkingStrategy as string) || null,
                    chunkingMaxSize: (chunking.chunkingMaxSize as number | undefined) ?? null,
                    chunkingOverlap: (chunking.chunkingOverlap as number | undefined) ?? null,
                    chunkingSeparators: (chunking.chunkingSeparators as string[]) ?? null,
                  },
                  labels.settings.saved,
                )
              }
            >
              {labels.settings.save}
            </Button>
          </div>
        </CardBody>
      </Card>

      {descriptor.inputFields.length > 0 && (
        <Card className="border-[var(--tone-wait)]/40">
          <CardHeader>
            <CardTitle>{labels.settings.scope}</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <div className="flex gap-2.5 rounded-lg bg-[var(--tone-wait-bg)] px-3 py-2.5">
              <AlertTriangle
                className="mt-0.5 size-4 shrink-0 text-[var(--tone-wait)]"
                aria-hidden
              />
              <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                {labels.settings.scopeWarning}
              </p>
            </div>

            <SchemaForm fields={descriptor.inputFields} values={inputs} onChange={setInputs} />

            <Toggle
              checked={backfill}
              onCheckedChange={setBackfill}
              label={labels.wizard.backfill}
              hint={labels.wizard.backfillHint}
            />

            <div className="flex justify-end">
              <Button
                variant="primary"
                size="sm"
                loading={patch.isPending}
                onClick={() =>
                  save(
                    { inputs, backfillEnabled: backfill },
                    'Rechecking the source — this may take a moment',
                  )
                }
              >
                {labels.settings.save}
              </Button>
            </div>
          </CardBody>
        </Card>
      )}

      {/* Webhooks are accepted by PATCH but there is no endpoint to receive them, so the
          controls stay hidden until the backend has one. See config/features.ts. */}
      {features.webhooks && null}

      {patch.error && <ErrorState error={patch.error} compact />}
    </div>
  )
}
