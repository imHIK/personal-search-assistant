import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import type { ChannelType } from '@/api/types'
import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { Field, Input, Select } from '@/components/ui/Input'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { channelDescriptors, channelFor, implementedChannels } from '@/config/channels'
import { friendlyLastError } from '@/config/errors'
import { initialValues, pruneEmpty, type FieldSpec } from '@/config/fields'
import { labels } from '@/config/labels'
import { presentChannel } from '@/config/presentation'
import { useChannel, useChannelMutations, useConnections } from '@/hooks/queries'
import { DeliveriesList } from './DeliveriesList'
import { notifyMutationError, notifyTestResult } from './notifyTest'

/**
 * Create or edit a channel. The destination fields and the kind of account it sends through come from
 * the type's descriptor in `config/channels.ts`, so a new platform needs no work here. `type` is fixed
 * after creation.
 */
export function ChannelFormPage() {
  const { id } = useParams<{ id: string }>()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const { data: existing, isLoading, error: loadError } = useChannel(id)
  const { create, patch, test } = useChannelMutations()
  const { data: allConnections } = useConnections()

  const firstImplemented = useMemo(() => implementedChannels()[0]?.id ?? 'EMAIL', [])
  const [type, setType] = useState<ChannelType>(firstImplemented)
  const [name, setName] = useState('')
  const [enabled, setEnabled] = useState(true)
  // '' is the default account — the same spelling the PATCH endpoint uses to un-pin one.
  const [connectionId, setConnectionId] = useState('')
  const [target, setTarget] = useState<FormValues>(() => initialValues(channelFor(firstImplemented).targetFields))
  const [nameError, setNameError] = useState<string>()
  const [targetErrors, setTargetErrors] = useState<Record<string, string>>({})

  const descriptor = channelFor(isEdit && existing ? existing.type : type)
  const account = descriptor.account
  const accounts = (allConnections ?? []).filter((connection) => connection.type === account?.id)

  useEffect(() => {
    if (!existing) return
    setName(existing.name)
    setType(existing.type)
    setEnabled(existing.enabled)
    setConnectionId(existing.connectionId ?? '')
    setTarget(initialValues(channelFor(existing.type).targetFields, existing.target))
  }, [existing])

  // A different type has a different destination shape and account kind, so both start empty.
  useEffect(() => {
    if (isEdit) return
    setTarget(initialValues(channelFor(type).targetFields))
    setTargetErrors({})
    setConnectionId('')
  }, [type, isEdit])

  const pending = create.isPending || patch.isPending
  const saveError = create.error ?? patch.error

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const missingName = !name.trim()
    const errors = missingRequired(descriptor.targetFields, target)
    setNameError(missingName ? labels.channels.nameRequired : undefined)
    setTargetErrors(errors)
    if (missingName || Object.keys(errors).length > 0) return

    // The whole target is sent on edit: the server replaces it, which is how a cleared optional
    // field (an emptied "Copy to") is actually removed.
    const body = { name: name.trim(), target: pruneEmpty(target), enabled }
    if (isEdit && id) {
      patch.mutate(
        { id, body: { ...body, ...(account ? { connectionId } : {}) } },
        {
          onSuccess: () => {
            toast.success(labels.channels.saved)
            navigate('/channels')
          },
        },
      )
    } else {
      create.mutate(
        { ...body, type, ...(connectionId ? { connectionId } : {}) },
        {
          onSuccess: (created) => {
            toast.success(labels.channels.created)
            navigate(`/channels/${created.id}`)
          },
        },
      )
    }
  }

  if (isEdit && isLoading) return <SkeletonList rows={2} />
  if (isEdit && loadError) return <ErrorState error={loadError} />

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <PageHeader
        title={isEdit ? labels.channels.editTitle(existing?.name ?? '') : labels.channels.add}
        subtitle={descriptor.description || labels.channels.subtitle}
        backTo="/channels"
        backLabel={labels.channels.title}
        actions={
          isEdit && existing ? (
            <div className="flex items-center gap-2">
              <StateBadge state={presentChannel(existing)} size="sm" />
              <Button
                variant="secondary"
                size="sm"
                title={labels.channels.testHint}
                loading={test.isPending}
                onClick={() =>
                  test.mutate(existing.id, { onSuccess: notifyTestResult, onError: notifyMutationError })
                }
              >
                {test.isPending ? labels.channels.testing : labels.channels.test}
              </Button>
            </div>
          ) : undefined
        }
      />

      {isEdit && existing?.status === 'ERROR' && existing.lastError && (
        <p className="rounded-lg bg-[var(--tone-alert-bg)] px-4 py-3 text-xs leading-relaxed text-[var(--tone-alert)]">
          {friendlyLastError(existing.lastError).detail}
        </p>
      )}

      <form onSubmit={submit} className="space-y-5">
        <Card>
          <CardHeader>
            <CardTitle>{labels.channels.basics}</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <Field label={labels.channels.name} required error={nameError} htmlFor="channel-name">
              <Input
                id="channel-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder={labels.channels.namePlaceholder}
                autoFocus
              />
            </Field>

            <Field label={labels.channels.type} required htmlFor="channel-type">
              {isEdit ? (
                <p className="flex h-9 items-center text-sm text-[var(--text-muted)]">{descriptor.label}</p>
              ) : (
                <Select
                  id="channel-type"
                  value={type}
                  onChange={(event) => setType(event.target.value as ChannelType)}
                >
                  {channelDescriptors.map((candidate) => (
                    <option key={candidate.id} value={candidate.id} disabled={!candidate.implemented}>
                      {candidate.implemented
                        ? candidate.label
                        : `${candidate.label} (${labels.channels.notAvailable})`}
                    </option>
                  ))}
                </Select>
              )}
            </Field>

            {account && (
              <Field label={labels.channels.account} hint={labels.channels.accountHint} htmlFor="channel-account">
                <Select
                  id="channel-account"
                  value={connectionId}
                  onChange={(event) => setConnectionId(event.target.value)}
                  disabled={pending}
                >
                  <option value="">{labels.channels.accountDefault(account.label)}</option>
                  {accounts.map((connection) => (
                    <option key={connection.id} value={connection.id}>
                      {connection.name}
                      {isDefaultConnection(connection) ? ' (default)' : ''}
                    </option>
                  ))}
                </Select>
                {allConnections && accounts.length === 0 && (
                  <p className="mt-1.5 text-xs text-[var(--tone-alert)]">
                    {labels.channels.accountNone(account.label)}{' '}
                    <Link to="/connections/new" className="font-medium underline underline-offset-2">
                      {labels.channels.accountConnect}
                    </Link>
                  </p>
                )}
              </Field>
            )}

            <Toggle
              checked={enabled}
              onCheckedChange={setEnabled}
              label={labels.channels.enabled}
              hint={labels.channels.enabledHint}
            />
          </CardBody>
        </Card>

        {descriptor.targetFields.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle>{labels.channels.destination}</CardTitle>
            </CardHeader>
            <CardBody>
              <SchemaForm
                fields={descriptor.targetFields}
                values={target}
                onChange={setTarget}
                errors={targetErrors}
                disabled={pending}
              />
            </CardBody>
          </Card>
        )}

        {saveError && <ErrorState error={saveError} compact />}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => navigate('/channels')}>
            {labels.common.cancel}
          </Button>
          <Button variant="primary" type="submit" loading={pending}>
            {isEdit ? labels.channels.save : labels.channels.create}
          </Button>
        </div>
      </form>

      {isEdit && id && <DeliveriesList channelId={id} />}
    </div>
  )
}

/** Required fields left empty, keyed by name. An empty list counts as empty. */
function missingRequired(fields: FieldSpec[], values: FormValues): Record<string, string> {
  const present = pruneEmpty(values)
  const errors: Record<string, string> = {}
  for (const field of fields) {
    if (field.required && !(field.name in present)) {
      errors[field.name] = `${field.label} is required`
    }
  }
  return errors
}
