import { HelpCircle } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import type { RateLimitPolicy } from '@/api/types'
import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { Field, Input, Select } from '@/components/ui/Input'
import { PageHeader } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { accountFor, accountTypes } from '@/config/accounts'
import { initialValues, pruneEmpty, type FieldSpec } from '@/config/fields'
import { labels } from '@/config/labels'
import { useConnection, useConnectionMutations } from '@/hooks/queries'
import { ConnectAccountButton } from './ConnectAccountButton'
import { RateLimitFields } from './RateLimitFields'

export function AccountFormPage() {
  const { id } = useParams<{ id: string }>()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const { data: existing, isLoading, error: loadError } = useConnection(id)
  const { create, patch } = useConnectionMutations()

  const candidates = useMemo(() => accountTypes(), [])
  const [type, setType] = useState<string>(candidates[0]?.id ?? 'GMAIL')
  const [name, setName] = useState('')
  const [makeDefault, setMakeDefault] = useState(false)
  const [auth, setAuth] = useState<FormValues>({})
  const [config, setConfig] = useState<FormValues>({})
  const [rateLimit, setRateLimit] = useState<RateLimitPolicy>({ rules: [] })
  const [nameError, setNameError] = useState<string>()

  const descriptor = accountFor(isEdit && existing ? existing.type : type)

  useEffect(() => {
    if (!existing) return
    setName(existing.name)
    setType(existing.type)
    setMakeDefault(isDefaultConnection(existing))
    const d = accountFor(existing.type)
    setAuth(initialValues(d.authFields, existing.auth as Record<string, unknown>))
    setConfig(initialValues(d.configFields, existing.config as Record<string, unknown>))
    setRateLimit({ rules: existing.rateLimit?.rules ?? [] })
  }, [existing])

  useEffect(() => {
    if (isEdit) return
    const d = accountFor(type)
    setAuth(initialValues(d.authFields))
    setConfig(initialValues(d.configFields))
  }, [type, isEdit])

  const pending = create.isPending || patch.isPending
  const saveError = create.error ?? patch.error

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!name.trim()) {
      setNameError('Give this account a name so you can recognise it later')
      return
    }
    setNameError(undefined)

    // Always sent, even empty: absent means unchanged, so `[]` is how a removed limit is saved.
    const body = {
      name: name.trim(),
      auth: pruneEmpty(auth),
      config: pruneEmpty(config),
      rateLimit,
    }

    if (isEdit && id) {
      patch.mutate(
        { id, body },
        {
          onSuccess: () => {
            toast.success('Account updated')
            navigate('/connections')
          },
        },
      )
    } else {
      create.mutate(
        { ...body, type, makeDefault },
        {
          onSuccess: () => {
            toast.success('Account connected')
            navigate('/connections')
          },
        },
      )
    }
  }

  if (isEdit && isLoading) return <SkeletonList rows={2} />
  if (isEdit && loadError) return <ErrorState error={loadError} />

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader
        title={isEdit ? `Edit ${existing?.name ?? 'account'}` : labels.accounts.add}
        subtitle={descriptor.description || labels.accounts.subtitle}
        backTo="/connections"
        backLabel={labels.accounts.title}
      />

      <form onSubmit={submit} className="space-y-5">
        <Card>
          <CardHeader>
            <CardTitle>Basics</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <Field
              label="Name"
              hint="Just for you — e.g. “Work Google” or “Personal Gmail”."
              required
              error={nameError}
              htmlFor="account-name"
            >
              <Input
                id="account-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Work Google"
                autoFocus
              />
            </Field>

            <Field
              label="Service"
              hint={isEdit ? 'Cannot be changed after creation.' : undefined}
              required
              htmlFor="account-type"
            >
              {isEdit ? (
                <p className="flex h-9 items-center text-sm text-[var(--text-muted)]">
                  {descriptor.label}
                </p>
              ) : (
                <Select
                  id="account-type"
                  value={type}
                  onChange={(event) => setType(event.target.value)}
                >
                  {candidates.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.label}
                    </option>
                  ))}
                </Select>
              )}
            </Field>

            {!isEdit && (
              <Toggle
                checked={makeDefault}
                onCheckedChange={setMakeDefault}
                label="Use this by default"
                hint={labels.accounts.defaultHint(descriptor.label)}
              />
            )}
          </CardBody>
        </Card>

        {(descriptor.authFields.length > 0 || descriptor.oauth) && (
          <Card>
            <CardHeader className="flex items-center justify-between gap-3">
              <CardTitle>Sign-in details</CardTitle>
              {descriptor.credentialHelp && <CredentialHelp descriptor={descriptor} />}
            </CardHeader>
            <CardBody className="space-y-4">
              {descriptor.oauth && (
                <ConnectAccountButton
                  descriptor={descriptor}
                  type={isEdit && existing ? existing.type : type}
                  connectionId={isEdit ? id : undefined}
                  name={name.trim() || undefined}
                  disabled={pending}
                />
              )}
              {descriptor.authFields.length > 0 && (
                <SchemaForm fields={descriptor.authFields} values={auth} onChange={setAuth} />
              )}
            </CardBody>
          </Card>
        )}

        {descriptor.configFields.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle>OAuth application</CardTitle>
            </CardHeader>
            <CardBody className="space-y-4">
              <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                Only needed if the server has no OAuth client configured. Leave blank to use the
                server's.
              </p>
              <SchemaForm fields={descriptor.configFields} values={config} onChange={setConfig} />
              <Technical>
                <RawBlobEditor label="Extra auth keys" values={auth} onChange={setAuth} />
              </Technical>
            </CardBody>
          </Card>
        )}

        <Card>
          <CardHeader>
            <CardTitle>{labels.accounts.rateLimitTitle}</CardTitle>
          </CardHeader>
          <CardBody className="space-y-4">
            <p className="text-xs leading-relaxed text-[var(--text-muted)]">
              {labels.accounts.rateLimitHint}
            </p>
            <RateLimitFields value={rateLimit} onChange={setRateLimit} disabled={pending} />
            {rateLimit.rules.length > 0 && (
              <p className="text-xs leading-relaxed text-[var(--text-subtle)]">
                {labels.accounts.rateLimitWarning}
              </p>
            )}
          </CardBody>
        </Card>

        {saveError && (
          <ErrorState
            error={saveError}
            compact
          />
        )}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => navigate('/connections')}>
            {labels.common.cancel}
          </Button>
          <Button variant="primary" type="submit" loading={pending}>
            {isEdit ? labels.settings.save : 'Connect'}
          </Button>
        </div>
      </form>
    </div>
  )
}

function CredentialHelp({ descriptor }: { descriptor: ReturnType<typeof accountFor> }) {
  const [open, setOpen] = useState(false)
  const help = descriptor.credentialHelp
  if (!help) return null

  return (
    <div className="relative">
      <Button type="button" variant="ghost" size="sm" onClick={() => setOpen((o) => !o)}>
        <HelpCircle />
        {labels.accounts.helpTitle}
      </Button>
      {open && (
        <div className="absolute right-0 top-9 z-30 w-80 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 shadow-[var(--shadow-lg)]">
          <p className="mb-2 text-sm font-semibold">{help.title}</p>
          <ol className="list-inside list-decimal space-y-1.5 text-xs leading-relaxed text-[var(--text-muted)]">
            {help.steps.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
          {help.scopes && (
            <>
              <p className="mb-1 mt-3 text-xs font-medium">Required scope</p>
              <ul className="space-y-0.5">
                {help.scopes.map((scope) => (
                  <li key={scope} className="break-all font-mono text-[11px] text-[var(--text-subtle)]">
                    {scope}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function RawBlobEditor({
  label,
  values,
  onChange,
}: {
  label: string
  values: FormValues
  onChange: (values: FormValues) => void
}) {
  const spec: FieldSpec[] = [
    {
      name: '__raw',
      kind: 'json',
      label,
      hint: 'Merged into the blob sent to the server. For keys this form does not know about.',
    },
  ]
  return (
    <SchemaForm
      fields={spec}
      values={{ __raw: {} }}
      onChange={(next) => onChange({ ...values, ...(next.__raw as FormValues) })}
    />
  )
}
