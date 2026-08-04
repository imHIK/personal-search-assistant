import { HelpCircle } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import type { SourceType } from '@/api/types'
import { SchemaForm, type FormValues } from '@/components/SchemaForm'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/Card'
import { Field, Input, Select } from '@/components/ui/Input'
import { PageHeader } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'
import { ErrorState, SkeletonList } from '@/components/ui/States'
import { connectorFor, connectorsNeedingAccounts } from '@/config/connectors'
import { initialValues, pruneEmpty, type FieldSpec } from '@/config/fields'
import { labels } from '@/config/labels'
import { useConnection, useConnectionMutations } from '@/hooks/queries'

/**
 * Create or edit an account. Both modes render the same descriptor-driven form — the only
 * differences are that `type` is fixed on edit, and that saving goes to POST vs PATCH.
 *
 * The whole form body comes from `descriptor.authFields` / `configFields`, so a new connector's
 * credential shape needs no work here at all.
 */
export function AccountFormPage() {
  const { id } = useParams<{ id: string }>()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const { data: existing, isLoading, error: loadError } = useConnection(id)
  const { create, patch } = useConnectionMutations()

  const candidates = useMemo(() => connectorsNeedingAccounts(), [])
  const [type, setType] = useState<SourceType>(candidates[0]?.id ?? 'GMAIL')
  const [name, setName] = useState('')
  const [makeDefault, setMakeDefault] = useState(false)
  const [auth, setAuth] = useState<FormValues>({})
  const [config, setConfig] = useState<FormValues>({})
  const [nameError, setNameError] = useState<string>()

  const descriptor = connectorFor(isEdit && existing ? existing.type : type)

  // Seed the form once the existing account arrives. Secrets round-trip from the server
  // unredacted, so they land in the masked `secret` controls rather than plain text.
  useEffect(() => {
    if (!existing) return
    setName(existing.name)
    setType(existing.type)
    setMakeDefault(isDefaultConnection(existing))
    const d = connectorFor(existing.type)
    setAuth(initialValues(d.authFields, existing.auth as Record<string, unknown>))
    setConfig(initialValues(d.configFields, existing.config as Record<string, unknown>))
  }, [existing])

  // Reset the credential fields when the type changes on a new account — the field set differs.
  useEffect(() => {
    if (isEdit) return
    const d = connectorFor(type)
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

    const body = {
      name: name.trim(),
      auth: pruneEmpty(auth),
      config: pruneEmpty(config),
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
                  onChange={(event) => setType(event.target.value as SourceType)}
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

        {descriptor.authFields.length > 0 && (
          <Card>
            <CardHeader className="flex items-center justify-between gap-3">
              <CardTitle>Sign-in details</CardTitle>
              {descriptor.credentialHelp && <CredentialHelp descriptor={descriptor} />}
            </CardHeader>
            <CardBody>
              <SchemaForm fields={descriptor.authFields} values={auth} onChange={setAuth} />
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

        {saveError && (
          <ErrorState
            error={saveError}
            compact
            // The backend verifies credentials before saving, so this is nearly always a
            // rejected-token 400 rather than a bug.
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

/** Collapsible, connector-specific instructions sourced from the descriptor. */
function CredentialHelp({ descriptor }: { descriptor: ReturnType<typeof connectorFor> }) {
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

/**
 * Escape hatch for credential keys no descriptor names yet — the backend treats `auth` and
 * `config` as opaque blobs, so a new connector can be exercised before it has a descriptor.
 */
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
